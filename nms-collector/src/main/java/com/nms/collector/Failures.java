package com.nms.collector;

/**
 * Renders an exception as something an operator can act on.
 *
 * <p>Exists because {@code getMessage()} is frequently null. A connection
 * refused surfacing through {@code HttpClient}, and several of the JDK's own
 * socket exceptions, carry no message at all -- so the obvious
 * {@code "unreachable: " + e.getMessage()} puts the word "null" in front of
 * whoever is trying to work out why a camera stopped reporting.
 *
 * <p>The class name is the fallback, because {@code ConnectException} or
 * {@code SocketTimeoutException} tells an operator materially more than "null"
 * does.
 */
public final class Failures {

    private Failures() {
    }

    /**
     * A readable description of why something failed.
     *
     * <p>Walks to the deepest cause with a message: wrapper exceptions are
     * usually the uninformative layer, and the message worth showing is
     * underneath them.
     */
    public static String describe(Throwable throwable) {
        if (throwable == null) {
            return "unknown failure";
        }

        Throwable current = throwable;
        Throwable deepestWithMessage = hasMessage(current) ? current : null;

        // Bounded: a self-referencing cause chain would otherwise loop, and
        // that has been seen in the wild from badly written libraries.
        for (int depth = 0; depth < 10 && current.getCause() != null
                && current.getCause() != current; depth++) {
            current = current.getCause();
            if (hasMessage(current)) {
                deepestWithMessage = current;
            }
        }

        // A message naming an exception class is kept rather than suppressed.
        // When a cause carries no message the JDK sets the wrapper's message to
        // the cause's toString(), so this yields "java.net.ConnectException" --
        // which tells an operator the connection was refused, and is strictly
        // more than the wrapper's own name would.
        return deepestWithMessage == null
                ? simpleName(throwable)
                : deepestWithMessage.getMessage().trim();
    }

    private static boolean hasMessage(Throwable throwable) {
        return throwable.getMessage() != null && !throwable.getMessage().isBlank();
    }

    private static String simpleName(Throwable throwable) {
        String name = throwable.getClass().getSimpleName();
        return name.isEmpty() ? throwable.getClass().getName() : name;
    }
}
