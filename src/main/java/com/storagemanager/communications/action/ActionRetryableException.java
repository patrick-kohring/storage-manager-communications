package com.storagemanager.communications.action;

/**
 * A transient failure while processing a {@link SendCommunicationAction}: the send seam raised
 * (Req 7.5) or the lead read seam raised while reading (Req 8.4). Surfacing this error leaves the
 * originating message eligible for redelivery (Req 9.2). Because one action produces exactly one
 * send, a retry re-attempts only that single send.
 *
 * <p>Owned by the {@code lead-communication-action} spec (Error Handling).
 */
public class ActionRetryableException extends ActionProcessingException {

    public ActionRetryableException(String message) {
        super(message);
    }

    public ActionRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
