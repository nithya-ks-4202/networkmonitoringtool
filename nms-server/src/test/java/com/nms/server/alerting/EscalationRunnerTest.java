package com.nms.server.alerting;

import com.nms.server.repository.CoreRepositories.EscalationRepository;
import com.nms.server.trigger.ProblemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the alerting loop, which failed in a way nothing else would have
 * caught.
 *
 * <p>The runner selects due escalations outside a transaction and advances
 * each one inside its own. When the selection returned entities, every one of
 * them arrived at {@code advance} detached, and the first lazy association
 * touched threw {@code could not initialize proxy ... no Session}. The server
 * started, reported itself healthy, opened problems correctly -- and sent no
 * alert, ever. The only evidence was one line per pass in a log nobody reads
 * until after the incident they were not told about.
 */
class EscalationRunnerTest {

    private final EscalationRepository escalations = mock(EscalationRepository.class);
    private final EscalationService escalationService = mock(EscalationService.class);
    private final ProblemService problemService = mock(ProblemService.class);

    private final EscalationRunner runner =
            new EscalationRunner(escalations, escalationService, problemService, 200);

    @Test
    @DisplayName("escalations are advanced by id, never as detached entities")
    void advancesByIdentifier() {
        when(escalations.findDueIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(7L, 8L));

        runner.run();

        // The identifiers go through untouched. Passing an entity here is the
        // bug: it would be detached, and its problem could not be read.
        verify(escalationService).advance(7L);
        verify(escalationService).advance(8L);
    }

    /**
     * One unhealthy escalation must not silence every other incident. This is
     * the property that turned a single fault into total alerting failure:
     * every escalation hit the same error, and each one was logged and
     * skipped, so the loop kept running and nothing was ever delivered.
     */
    @Test
    void oneFailingEscalationDoesNotStopTheRest() {
        when(escalations.findDueIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("could not initialize proxy"))
                .when(escalationService).advance(2L);

        runner.run();

        verify(escalationService).advance(1L);
        verify(escalationService).advance(2L);
        verify(escalationService).advance(3L);
    }

    /**
     * Suppressions are lifted before the due list is read, so a problem
     * released this pass escalates in this pass. Doing it after would add a
     * whole interval of silence to every maintenance window that ends.
     */
    @Test
    void liftsSuppressionsBeforeSelectingDueEscalations() {
        when(problemService.releaseExpiredSuppressions(any(Instant.class))).thenReturn(2);
        when(escalations.findDueIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        runner.run();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(problemService, escalations);
        order.verify(problemService).releaseExpiredSuppressions(any(Instant.class));
        order.verify(escalations).findDueIds(any(Instant.class), any(Pageable.class));
    }

    /**
     * A failure selecting the batch must not kill the scheduled thread. If it
     * propagated, Spring would stop rescheduling the task and alerting would
     * be dead until the next restart, with nothing to indicate why.
     */
    @Test
    void survivesAFailureSelectingTheBatch() {
        when(problemService.releaseExpiredSuppressions(any(Instant.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        runner.run();

        verify(escalationService, never()).advance(anyLong());
    }

    @Test
    void honoursTheConfiguredBatchSize() {
        EscalationRunner limited =
                new EscalationRunner(escalations, escalationService, problemService, 50);
        when(escalations.findDueIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        limited.run();

        org.mockito.ArgumentCaptor<Pageable> page =
                org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(escalations).findDueIds(any(Instant.class), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(50);
    }
}
