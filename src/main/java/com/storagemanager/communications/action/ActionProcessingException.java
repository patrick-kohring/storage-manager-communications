package com.storagemanager.communications.action;

/**
 * Base type for all processing errors raised while interpreting a
 * {@link SendCommunicationAction}. Subtypes distinguish terminal rejections
 * ({@link ActionValidationException}) from transient failures eligible for redelivery
 * ({@link ActionRetryableException}).
 *
 * <p>Owned by the {@code lead-communication-action} spec (Error Handling).
 */
public abstract class ActionProcessingException extends RuntimeException {

    protected ActionProcessingException(String message) {
        super(message);
    }

    protected ActionProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
