package com.nms.collector;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case that motivated this class: an operator opening the interface to find
 * out why a camera stopped reporting, and being told "ONVIF unreachable: null".
 */
class FailuresTest {

    @Test
    void fallsBackToTheClassNameWhenThereIsNoMessage() {
        // HttpClient surfaces a refused connection this way, with no message.
        String description = Failures.describe(new ConnectException());

        assertThat(description).isEqualTo("ConnectException");
        assertThat(description).doesNotContain("null");
    }

    @Test
    void usesTheMessageWhenThereIsOne() {
        assertThat(Failures.describe(new SocketTimeoutException("connect timed out")))
                .isEqualTo("connect timed out");
    }

    @Test
    void reachesPastAWrapperWithNoMessageOfItsOwn() {
        // The informative message is usually underneath the wrapper, not on it.
        Throwable wrapped = new IOException(new ConnectException("Connection refused"));

        assertThat(Failures.describe(wrapped)).isEqualTo("Connection refused");
    }

    @Test
    void prefersTheDeepestMessageInAChain() {
        Throwable chain = new IOException("outer",
                new IllegalStateException("middle",
                        new ConnectException("Network is unreachable")));

        assertThat(Failures.describe(chain)).isEqualTo("Network is unreachable");
    }

    @Test
    void namesTheCauseWhenNothingCarriesAnExplicitMessage() {
        // Constructing an exception from a bare cause makes the JDK set the
        // wrapper's message to the cause's toString(), so the class name of the
        // real failure survives -- which is what an operator needs.
        Throwable chain = new IOException(new ConnectException());

        assertThat(Failures.describe(chain)).contains("ConnectException");
        assertThat(Failures.describe(chain)).doesNotContain("null");
    }

    @Test
    void survivesASelfReferencingCause() {
        // Badly written libraries do produce these, and a naive walk loops.
        Exception loop = new IOException("looping") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(Failures.describe(loop)).isEqualTo("looping");
    }

    @Test
    void handlesNull() {
        assertThat(Failures.describe(null)).isEqualTo("unknown failure");
    }

    @Test
    void neverReturnsSomethingContainingNull() {
        // The property that matters. Every one of these reached an operator as
        // "unreachable: null" before this class existed.
        for (Throwable throwable : new Throwable[]{
                new ConnectException(),
                new SocketTimeoutException(),
                new IOException(),
                new IOException(new ConnectException()),
                new IOException((String) null),
        }) {
            assertThat(Failures.describe(throwable))
                    .as("describing %s", throwable.getClass().getSimpleName())
                    .isNotBlank()
                    .doesNotContain("null");
        }
    }
}
