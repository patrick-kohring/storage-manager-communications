package com.storagemanager.communications.inbox;

import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.storagemanager.communications.action.ActionRetryableException;
import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.messaging.api.InboundMessage;

/**
 * The inbox handler seam (Req 2.1): a Consumer&lt;InboundMessage&gt; that routes an admitted message
 * to its registered InboxHandler by eventType only (Req 2.2, 2.4), reconstructs the EventEnvelope
 * (Req 3), invokes the handler exactly once (Req 2.3), and maps the handler outcome onto
 * {@code processOnce} (Req 5, 6).
 *
 * <p>Outcome mapping (total function over the five outcomes):
 * <ul>
 *   <li>success             -&gt; return normally  -&gt; processed_at stamped   (Req 5.1)</li>
 *   <li>ActionRetryable     -&gt; re-throw         -&gt; processed_at NOT stamped, redelivered (Req 5.2)</li>
 *   <li>ActionValidation    -&gt; record + return  -&gt; processed_at stamped, terminal (Req 5.3, 5.4)</li>
 *   <li>reconstruction fail  -&gt; record + return -&gt; processed_at stamped, terminal (Req 3.6, 3.7)</li>
 *   <li>unmatched eventType  -&gt; record + return -&gt; processed_at stamped, no handler (Req 6)</li>
 * </ul>
 *
 * <p>The single rule the whole design turns on: {@link ActionRetryableException} is the ONLY
 * exception that escapes {@code accept(...)}/{@code processOnce}. Every other outcome returns
 * normally so {@code IdempotentInbox} stamps {@code processed_at}. The Idempotent_Inbox uniqueness,
 * dedupe, insert-on-receive, and stamping mechanics are owned by the {@code messaging} starter and
 * are left unchanged (Req 5.6).
 */
@Component
public class DispatchingHandler implements Consumer<InboundMessage> {

    private static final Logger log = LoggerFactory.getLogger(DispatchingHandler.class);

    private final HandlerRegistry registry;
    private final EnvelopeReconstructor reconstructor;

    public DispatchingHandler(HandlerRegistry registry, EnvelopeReconstructor reconstructor) {
        this.registry = registry;
        this.reconstructor = reconstructor;
    }

    @Override
    public void accept(InboundMessage message) {
        // Route by eventType ONLY — never inspect the payload (Req 2.2, 2.4).
        Optional<InboxHandler> handler = registry.lookup(message.eventType());

        if (handler.isEmpty()) {
            // Unmatched_Message: consume as a terminal processed no-op; record the eventType
            // (Req 6.1, 6.2) and invoke no handler (Req 6.3). Returning normally stamps processed_at.
            log.warn("Unmatched inbox eventType '{}' for messageId {}; no handler registered, "
                    + "consuming as terminal no-op", message.eventType(), message.messageId());
            return;
        }

        // Reconstruct the EventEnvelope the handler expects (Req 3.1). A terminal reconstruction
        // failure is consumed as a processed no-op with the reason + offending field recorded
        // (Req 3.6, 3.7); no handler is invoked.
        final EventEnvelope envelope;
        try {
            envelope = reconstructor.reconstruct(message);
        } catch (ReconstructionException ex) {
            log.warn("Reconstruction failed for messageId {} (field '{}'): {}; consuming as "
                    + "terminal no-op", message.messageId(), ex.field(), ex.getMessage());
            return; // processed_at stamped, not redelivered (Req 3.6)
        }

        // Invoke the matched handler exactly once (Req 2.3) and map its outcome onto processOnce
        // (Req 5). accept(...) stays as-is; the classifying try/catch lives in invokeHandler.
        invokeHandler(handler.get(), envelope, message);
    }

    /**
     * Invoke the matched handler exactly once with the reconstructed envelope (Req 2.3) and map its
     * outcome onto {@code processOnce} (Req 5).
     *
     * <ul>
     *   <li>success (normal return) -&gt; return normally so {@code processed_at} is stamped (Req 5.1)</li>
     *   <li>{@link ActionRetryableException} -&gt; re-throw so it escapes {@code processOnce},
     *       {@code processed_at} is NOT stamped, and the message is redelivered (Req 5.2) — the ONLY
     *       exception that escapes</li>
     *   <li>{@link ActionValidationException} -&gt; record the terminal rejection reason (Req 5.4),
     *       then return normally so {@code processed_at} is stamped and the message is not redelivered
     *       (Req 5.3). The recording is wrapped in its own try/catch so a recording failure cannot
     *       propagate and cause redelivery (Req 5.5)</li>
     * </ul>
     *
     * <p>The Idempotent_Inbox keying, insert-on-receive, and stamping rules are untouched (Req 5.6).
     */
    private void invokeHandler(InboxHandler handler, EventEnvelope envelope, InboundMessage message) {
        try {
            handler.handle(envelope);
            // Success: return normally so processOnce stamps processed_at (Req 5.1).
        } catch (ActionRetryableException ex) {
            // Transient: propagate out of processOnce so processed_at is NOT stamped and the message
            // is redelivered (Req 5.2). This is the ONLY exception that escapes.
            throw ex;
        } catch (ActionValidationException ex) {
            // Terminal rejection (poison): record the reason, then return normally so processed_at is
            // stamped and the message is not redelivered (Req 5.3, 5.4). A failure WHILE recording must
            // not cause redelivery (Req 5.5), so recording is best-effort inside this inner try/catch.
            try {
                log.warn("Terminal rejection for messageId {} eventType '{}': {}; consuming "
                        + "(processed, not redelivered)", message.messageId(),
                        message.eventType(), ex.getMessage());
            } catch (RuntimeException recordingFailure) {
                // Swallow: diagnostics recording must not turn a terminal into a redelivery (Req 5.5).
            }
            // return normally
        }
    }
}
