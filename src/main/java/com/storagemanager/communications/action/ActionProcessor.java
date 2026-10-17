package com.storagemanager.communications.action;

import com.storagemanager.communications.messaging.EventEnvelope;

/**
 * Interprets a {@code SendCommunication} action carried inside an {@link EventEnvelope} and performs
 * exactly one audited send through the reused {@code communications-api} send seam (Requirements 2,
 * 7). In step 5 (External Assumption A2) this will be invoked as the inbox {@code processOnce}
 * handler for {@code eventType == SendCommunicationAction.EVENT_TYPE}; this interface defines the
 * contract that wiring will call.
 *
 * <p>The outcome of processing is one of three things:
 * <ul>
 *   <li>a returned {@link ActionResult} — the single {@code ResolvedRecipient} was dispatched
 *       exactly once and the originating message may be marked processed (Req 7.4);</li>
 *   <li>a thrown {@link ActionValidationException} — a terminal rejection; the send seam is invoked
 *       zero times (Req 9.1) and the identical action would always fail, so it is not retried;</li>
 *   <li>a thrown {@link ActionRetryableException} — a transient failure (the send seam raised,
 *       Req 7.5, or the lead read seam raised, Req 8.4); the originating message remains eligible
 *       for redelivery (Req 9.2).</li>
 * </ul>
 *
 * <p>Owned by the {@code lead-communication-action} spec.
 */
public interface ActionProcessor {

    /**
     * Interprets the {@code SendCommunication} action carried in the envelope's {@code payload} and
     * performs exactly one send.
     *
     * @param envelope the canonical {@link EventEnvelope} whose {@code eventType} equals
     *                 {@link SendCommunicationAction#EVENT_TYPE} and whose {@code payload} carries
     *                 the {@link SendCommunicationAction} (Req 2.1)
     * @return an {@link ActionResult} indicating the action was successfully processed (Req 7.4)
     * @throws ActionValidationException a terminal rejection; the send seam is invoked zero times
     *                                   (Req 9.1) and the action is not redelivered
     * @throws ActionRetryableException  a transient failure; the originating message remains
     *                                   eligible for redelivery (Req 9.2)
     */
    ActionResult process(EventEnvelope envelope);
}
