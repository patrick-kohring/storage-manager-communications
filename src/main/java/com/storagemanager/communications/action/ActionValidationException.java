package com.storagemanager.communications.action;

/**
 * A terminal rejection of a {@link SendCommunicationAction}: a deterministic defect in the action,
 * lead data, template, or placeholders. The send seam is invoked zero times (Req 9.1), and because
 * re-running the identical action would fail identically, the originating message is marked processed
 * rather than redelivered.
 *
 * <p>Owned by the {@code lead-communication-action} spec (Error Handling).
 */
public class ActionValidationException extends ActionProcessingException {

    public ActionValidationException(String message) {
        super(message);
    }

    public ActionValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
