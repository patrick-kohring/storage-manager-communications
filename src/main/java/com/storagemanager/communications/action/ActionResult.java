package com.storagemanager.communications.action;

/**
 * The outcome of successfully processing a {@link SendCommunicationAction}: the single
 * {@code ResolvedRecipient} was dispatched through the send seam exactly once, so the originating
 * inbox message may be marked processed exactly once (Requirement 7.4).
 *
 * <p>A validation rejection ({@link ActionValidationException}) or a transient failure
 * ({@link ActionRetryableException}) is signalled by throwing rather than by returning an
 * {@code ActionResult}; an {@code ActionResult} instance therefore always represents a successful,
 * audited send.
 *
 * @param processed always {@code true} for a returned result; a successfully processed action
 *                  carries a processed indicator so callers can assert the processed outcome
 */
public record ActionResult(boolean processed) {

    private static final ActionResult PROCESSED = new ActionResult(true);

    /**
     * Returns the result representing a successfully processed action (Req 7.4).
     *
     * @return a processed {@code ActionResult}
     */
    public static ActionResult processedResult() {
        return PROCESSED;
    }
}
