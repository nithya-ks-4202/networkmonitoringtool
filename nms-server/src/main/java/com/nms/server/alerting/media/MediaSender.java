package com.nms.server.alerting.media;

import com.nms.server.domain.Alert;
import com.nms.server.domain.MediaType;
import com.nms.server.domain.MediaTypeKind;

/**
 * Delivers an alert over one channel.
 *
 * <p>Implementations must distinguish a permanent failure from a temporary one.
 * Retrying a rejected address wastes attempts that a transient SMTP outage
 * needs, and giving up on a transient outage loses the alert entirely.
 */
public interface MediaSender {

    /** The channel kind this sender implements. */
    MediaTypeKind kind();

    /**
     * Attempts delivery.
     *
     * @throws TransientDeliveryException when a retry could succeed
     * @throws PermanentDeliveryException when it could not
     */
    void send(Alert alert, MediaType mediaType) throws DeliveryException;

    /** Base type for delivery failures. */
    abstract class DeliveryException extends Exception {
        protected DeliveryException(String message) {
            super(message);
        }

        protected DeliveryException(String message, Throwable cause) {
            super(message, cause);
        }

        /** Whether the alerter should schedule another attempt. */
        public abstract boolean isRetryable();
    }

    /** The channel was unavailable; another attempt may work. */
    class TransientDeliveryException extends DeliveryException {
        public TransientDeliveryException(String message) {
            super(message);
        }

        public TransientDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean isRetryable() {
            return true;
        }
    }

    /** The message or its destination is invalid; retrying cannot help. */
    class PermanentDeliveryException extends DeliveryException {
        public PermanentDeliveryException(String message) {
            super(message);
        }

        public PermanentDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean isRetryable() {
            return false;
        }
    }
}
