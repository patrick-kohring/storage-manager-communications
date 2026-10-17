package com.storagemanager.communications.inbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.messaging.api.InboundMessage;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Edge-case fault-injection test for the design's <b>Error Handling</b> rule "Diagnostics never
 * cause redelivery (Req 5.5)" and <b>Testing Strategy (5.5 edge case, fault injection)</b>: even
 * when recording the terminal rejection reason itself fails, {@link DispatchingHandler#accept}
 * returns normally (does not propagate) so {@code processOnce} stamps {@code processed_at} and the
 * terminally rejected message is not redelivered.
 *
 * <p><b>Validates: Requirements 5.5</b>
 *
 * <h2>Approach</h2>
 * The terminal-rejection recording obligation (Req 5.4) is satisfied by a {@code log.warn(...)} on
 * the {@link DispatchingHandler} logger (Decision D2 — diagnostics are a log obligation only). To
 * inject a fault into that recording path, this test attaches a Logback {@link AppenderBase} to the
 * {@code DispatchingHandler} logger whose {@code append(...)} throws a {@link RuntimeException},
 * simulating a recording/logging failure <em>during</em> terminal-rejection recording.
 *
 * <p>A stub {@link InboxHandler} is registered whose {@code handle(...)} throws
 * {@link ActionValidationException} (the terminal outcome that triggers recording). The
 * {@link DispatchingHandler} is assembled in-test over a real {@link HandlerRegistry} and
 * {@link EnvelopeReconstructor}, and driven with an otherwise-valid matched {@link InboundMessage}
 * so reconstruction succeeds and the terminal arm is reached. The assertion is that
 * {@code accept(...)} completes normally — proving a recording failure cannot escape
 * {@code processOnce} and force redelivery (Req 5.5).
 *
 * <p>The throwing appender is detached in {@code @AfterEach} (try/finally semantics via JUnit
 * lifecycle) so it cannot poison other tests.
 */
class TerminalRejectionRecordingFaultInjectionTest {

    private static final String SEND_COMMUNICATION = "SendCommunication";

    private final Logger dispatchingHandlerLogger =
            (Logger) LoggerFactory.getLogger(DispatchingHandler.class);

    private ThrowingAppender throwingAppender;

    @BeforeEach
    void attachThrowingAppender() {
        throwingAppender = new ThrowingAppender();
        throwingAppender.setContext(dispatchingHandlerLogger.getLoggerContext());
        throwingAppender.start();
        dispatchingHandlerLogger.addAppender(throwingAppender);
    }

    @AfterEach
    void detachThrowingAppender() {
        // Clean up so the throwing appender never poisons another test.
        dispatchingHandlerLogger.detachAppender(throwingAppender);
        throwingAppender.stop();
    }

    @Test
    void recordingFailureDuringTerminalRejectionDoesNotPropagate() {
        // A matched handler whose handle(...) throws the terminal ActionValidationException, which is
        // what drives the DispatchingHandler into its terminal-rejection recording path (Req 5.3/5.4).
        InboxHandler terminalHandler = new InboxHandler() {
            @Override
            public String eventType() {
                return SEND_COMMUNICATION;
            }

            @Override
            public void handle(EventEnvelope envelope) {
                throw new ActionValidationException("deterministic defect: missing recipient");
            }
        };

        DispatchingHandler dispatchingHandler = new DispatchingHandler(
                new HandlerRegistry(List.of(terminalHandler)),
                new EnvelopeReconstructor());

        InboundMessage message = validMatchedMessage();

        // The throwing appender guarantees the recording log.warn(...) raises a RuntimeException.
        // DispatchingHandler must still return normally so processed_at is stamped (Req 5.5).
        assertThatCode(() -> dispatchingHandler.accept(message)).doesNotThrowAnyException();

        // Sanity: the recording path was actually exercised (the fault was actually injected),
        // otherwise a swallow of the fault would be vacuous.
        assertThat(throwingAppender.appendAttempts.get())
                .as("the terminal-rejection recording path must have been reached and attempted")
                .isGreaterThanOrEqualTo(1);
    }

    // =====================================================================
    // Fault-injection appender — throws on every append (recording failure)
    // =====================================================================

    /**
     * A Logback appender that counts and then fails every append, simulating a recording/logging
     * failure during terminal-rejection recording.
     */
    private static final class ThrowingAppender extends AppenderBase<ILoggingEvent> {

        private final AtomicInteger appendAttempts = new AtomicInteger();

        @Override
        protected void append(ILoggingEvent eventObject) {
            appendAttempts.incrementAndGet();
            throw new RuntimeException("injected recording failure");
        }
    }

    // =====================================================================
    // Fixtures — a valid, matched, reconstructable InboundMessage
    // =====================================================================

    private static InboundMessage validMatchedMessage() {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put("organization_id", UUID.randomUUID());

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        headers.put("entity_type", "lead");
        headers.put("entity_id", UUID.randomUUID().toString());
        headers.put("actor_type", ActorType.SYSTEM.name());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", SEND_COMMUNICATION);

        return new InboundMessage(
                UUID.randomUUID(),      // messageId
                SEND_COMMUNICATION,     // eventType -> matches the registered handler
                payload,
                scope,
                UUID.randomUUID(),      // correlationId
                headers);
    }
}
