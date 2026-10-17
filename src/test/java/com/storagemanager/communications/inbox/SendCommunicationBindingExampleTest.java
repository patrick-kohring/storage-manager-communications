package com.storagemanager.communications.inbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.storagemanager.communications.action.ActionProcessor;
import com.storagemanager.communications.action.ActionResult;
import com.storagemanager.communications.action.ActionRetryableException;
import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;

/**
 * Example (JUnit) tests for the design's <b>Testing Strategy (4.1 / 4.2 example)</b> —
 * {@link SendCommunicationBinding} as the single concrete {@link InboxHandler} this spec ships
 * (inbox-action-dispatch spec, Task 5.2). The binding is a thin adapter: it declares the
 * {@code "SendCommunication"} eventType and delegates {@code handle(envelope)} to
 * {@link ActionProcessor#process(EventEnvelope)}, carrying no outcome policy of its own.
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.3, 4.4</b>
 *
 * <ul>
 *   <li><b>Req 4.1</b> — {@code eventType()} equals {@code SendCommunicationAction.EVENT_TYPE}
 *       ({@code "SendCommunication"});</li>
 *   <li><b>Req 4.2 / 4.4</b> — {@code handle(envelope)} invokes {@code process(...)} exactly once
 *       with the passed envelope instance, and a normal {@link ActionResult} return completes the
 *       binding normally;</li>
 *   <li><b>Req 4.3</b> — the binding does not catch or translate the processor's classified
 *       exceptions: {@link ActionValidationException} and {@link ActionRetryableException} both
 *       propagate uncaught (the {@link DispatchingHandler} maps them centrally).</li>
 * </ul>
 *
 * <h2>Approach</h2>
 * A hand-written counting/recording stub {@link ActionProcessor} (not Mockito — the module's inbox
 * tests use hand-written stubs) records how many times {@code process(...)} was called and captures
 * the envelope it received, and is configurable to return an {@link ActionResult} or to throw either
 * classified exception. The binding is constructed directly ({@code new SendCommunicationBinding(...)})
 * with no Spring context.
 */
class SendCommunicationBindingExampleTest {

    // =====================================================================
    // Req 4.1 — eventType() equals "SendCommunication"
    // =====================================================================

    @Test
    void eventTypeEqualsSendCommunication() {
        SendCommunicationBinding binding = new SendCommunicationBinding(new CountingStubProcessor());

        assertThat(binding.eventType())
                .as("the binding declares SendCommunicationAction.EVENT_TYPE (Req 4.1)")
                .isEqualTo("SendCommunication");
    }

    // =====================================================================
    // Req 4.2 / 4.4 — handle delegates to process exactly once with the same envelope
    // =====================================================================

    @Test
    void handleInvokesProcessExactlyOnceWithThePassedEnvelope() {
        CountingStubProcessor processor = new CountingStubProcessor();
        processor.returning(ActionResult.processedResult());
        SendCommunicationBinding binding = new SendCommunicationBinding(processor);

        EventEnvelope envelope = sampleEnvelope();

        binding.handle(envelope);

        assertThat(processor.processCallCount())
                .as("handle(...) delegates to ActionProcessor.process exactly once (Req 4.2)")
                .isEqualTo(1);
        assertThat(processor.lastEnvelope())
                .as("process(...) receives the same envelope instance the binding was handed (Req 4.2)")
                .isSameAs(envelope);
    }

    // =====================================================================
    // Req 4.3 — classified exceptions propagate uncaught
    // =====================================================================

    @Test
    void validationExceptionPropagatesUncaught() {
        CountingStubProcessor processor = new CountingStubProcessor();
        ActionValidationException terminal = new ActionValidationException("terminal rejection");
        processor.throwing(terminal);
        SendCommunicationBinding binding = new SendCommunicationBinding(processor);

        assertThatThrownBy(() -> binding.handle(sampleEnvelope()))
                .as("the binding does not catch/translate a terminal ActionValidationException (Req 4.3)")
                .isSameAs(terminal);

        assertThat(processor.processCallCount())
                .as("the binding still delegated exactly once before the exception propagated")
                .isEqualTo(1);
    }

    @Test
    void retryableExceptionPropagatesUncaught() {
        CountingStubProcessor processor = new CountingStubProcessor();
        ActionRetryableException retryable = new ActionRetryableException("transient failure");
        processor.throwing(retryable);
        SendCommunicationBinding binding = new SendCommunicationBinding(processor);

        assertThatThrownBy(() -> binding.handle(sampleEnvelope()))
                .as("the binding does not catch/translate a transient ActionRetryableException (Req 4.3)")
                .isSameAs(retryable);

        assertThat(processor.processCallCount())
                .as("the binding still delegated exactly once before the exception propagated")
                .isEqualTo(1);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static EventEnvelope sampleEnvelope() {
        return new EventEnvelope(
                UUID.randomUUID(),      // eventId
                "SendCommunication",    // eventType
                1,                      // schemaVersion
                Instant.now(),          // occurredAt
                UUID.randomUUID(),      // organizationId
                null,                   // facilityId
                "Lead",                 // entityType
                UUID.randomUUID(),      // entityId
                ActorType.SYSTEM,       // actorType
                null,                   // actorId
                UUID.randomUUID(),      // correlationId
                null,                   // causationId
                Map.of("key", "value") // payload
        );
    }

    /**
     * A hand-written stub {@link ActionProcessor} that counts {@code process(...)} invocations,
     * captures the last envelope it received, and is configurable to return an {@link ActionResult}
     * or to throw a supplied {@link RuntimeException} (either classified exception).
     */
    private static final class CountingStubProcessor implements ActionProcessor {
        private int processCallCount;
        private EventEnvelope lastEnvelope;
        private ActionResult result = ActionResult.processedResult();
        private RuntimeException toThrow;

        void returning(ActionResult result) {
            this.result = result;
            this.toThrow = null;
        }

        void throwing(RuntimeException ex) {
            this.toThrow = ex;
        }

        @Override
        public ActionResult process(EventEnvelope envelope) {
            processCallCount++;
            lastEnvelope = envelope;
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }

        int processCallCount() {
            return processCallCount;
        }

        EventEnvelope lastEnvelope() {
            return lastEnvelope;
        }
    }
}
