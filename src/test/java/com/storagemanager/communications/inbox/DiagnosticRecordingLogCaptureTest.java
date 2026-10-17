package com.storagemanager.communications.inbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.messaging.api.InboundMessage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Example (JUnit, log-capture) tests for the design's <b>Testing Strategy (3.7 / 5.4 / 6.2 example,
 * log capture)</b> — the {@link DispatchingHandler}'s three diagnostic-recording obligations. Each
 * case drives a real {@code DispatchingHandler} (built with a real {@link HandlerRegistry} and real
 * {@link EnvelopeReconstructor}) and asserts, via a Logback {@link ListAppender} attached to the
 * {@code DispatchingHandler} logger, that the offending diagnostic value appears in a captured
 * {@code WARN} log event. The diagnostic obligation is a log-only obligation (Decision D2): there is
 * no dead-letter entity, table, or row.
 *
 * <p><b>Validates: Requirements 3.7, 5.4, 6.2</b>
 *
 * <ul>
 *   <li><b>Req 3.7</b> — a reconstruction failure records the offending <em>field</em> name;</li>
 *   <li><b>Req 5.4</b> — a terminal {@link ActionValidationException} records the rejection
 *       <em>reason</em> named by the exception;</li>
 *   <li><b>Req 6.2</b> — an unmatched message records the unmatched <em>eventType</em> value.</li>
 * </ul>
 *
 * <h2>Approach</h2>
 * A single stub {@link InboxHandler} is registered under {@code "SendCommunication"}. Its behavior is
 * controlled per test: in the terminal-rejection case it throws an {@link ActionValidationException}
 * with a known reason; in the reconstruction-failure case it is never reached (reconstruction throws
 * first); in the unmatched case no handler matches the message's eventType, so the stub is never
 * consulted. All three cases return normally from {@code accept(...)} (the diagnostic obligations
 * never propagate), so the assertions focus solely on the captured log output.
 */
class DiagnosticRecordingLogCaptureTest {

    private static final String SEND_COMMUNICATION = "SendCommunication";
    private static final String ORG = "organization_id";

    private final Logger dispatchingHandlerLogger =
            (Logger) LoggerFactory.getLogger(DispatchingHandler.class);
    private final ListAppender<ILoggingEvent> logCapture = new ListAppender<>();

    /** A stub handler whose {@link #handle} behavior each test configures. */
    private static final class ConfigurableHandler implements InboxHandler {
        private RuntimeException toThrow;

        void throwing(RuntimeException ex) {
            this.toThrow = ex;
        }

        @Override
        public String eventType() {
            return SEND_COMMUNICATION;
        }

        @Override
        public void handle(EventEnvelope envelope) {
            if (toThrow != null) {
                throw toThrow;
            }
        }
    }

    private final ConfigurableHandler handler = new ConfigurableHandler();

    private final DispatchingHandler dispatcher =
            new DispatchingHandler(new HandlerRegistry(List.of(handler)), new EnvelopeReconstructor());

    @BeforeEach
    void attachLogCapture() {
        logCapture.start();
        dispatchingHandlerLogger.addAppender(logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        dispatchingHandlerLogger.detachAppender(logCapture);
        logCapture.stop();
    }

    // =====================================================================
    // Req 3.7 — reconstruction failure records the offending field
    // =====================================================================

    @Test
    void reconstructionFailureRecordsTheOffendingField() {
        // A message matched by the registered "SendCommunication" handler but with a corrupt required
        // header (non-UUID entity_id) so reconstruction throws before the handler is invoked.
        LinkedHashMap<String, String> headers = validHeaders();
        headers.put("entity_id", "not-a-uuid");
        InboundMessage message = messageWith(SEND_COMMUNICATION, validScope(), headers);

        dispatcher.accept(message); // returns normally; records the field as a WARN

        assertThat(warnMessages())
                .as("a reconstruction failure records the offending field name (Req 3.7)")
                .anySatisfy(logged -> assertThat(logged).contains("entity_id"));
    }

    // =====================================================================
    // Req 5.4 — terminal ActionValidationException records the rejection reason
    // =====================================================================

    @Test
    void terminalRejectionRecordsTheRejectionReason() {
        String rejectionReason = "recipient email is missing for lead";
        handler.throwing(new ActionValidationException(rejectionReason));

        // A valid, matched message so reconstruction succeeds and the handler is invoked.
        InboundMessage message = messageWith(SEND_COMMUNICATION, validScope(), validHeaders());

        dispatcher.accept(message); // returns normally; records the rejection reason as a WARN

        assertThat(warnMessages())
                .as("a terminal ActionValidationException records its rejection reason (Req 5.4)")
                .anySatisfy(logged -> assertThat(logged).contains(rejectionReason));
    }

    // =====================================================================
    // Req 6.2 — unmatched message records the eventType
    // =====================================================================

    @Test
    void unmatchedMessageRecordsTheEventType() {
        String unmatchedEventType = "NoHandlerRegisteredForThisType";
        // No handler is registered for this eventType, so the dispatcher records it and returns.
        InboundMessage message = messageWith(unmatchedEventType, validScope(), validHeaders());

        dispatcher.accept(message); // returns normally; records the unmatched eventType as a WARN

        assertThat(warnMessages())
                .as("an unmatched message records the unmatched eventType value (Req 6.2)")
                .anySatisfy(logged -> assertThat(logged).contains(unmatchedEventType));
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** The fully-rendered WARN log messages captured from the DispatchingHandler logger. */
    private List<String> warnMessages() {
        return logCapture.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static LinkedHashMap<String, UUID> validScope() {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put(ORG, UUID.randomUUID());
        return scope;
    }

    private static LinkedHashMap<String, String> validHeaders() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        headers.put("entity_type", "lead");
        headers.put("entity_id", UUID.randomUUID().toString());
        headers.put("actor_type", ActorType.SYSTEM.name());
        return headers;
    }

    private static InboundMessage messageWith(String eventType, LinkedHashMap<String, UUID> scope,
            LinkedHashMap<String, String> headers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", SEND_COMMUNICATION);
        return new InboundMessage(
                UUID.randomUUID(),  // messageId
                eventType,
                payload,
                scope,
                UUID.randomUUID(),  // correlationId
                headers);
    }
}
