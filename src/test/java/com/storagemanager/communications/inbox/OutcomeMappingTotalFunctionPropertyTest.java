package com.storagemanager.communications.inbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.storagemanager.communications.action.ActionRetryableException;
import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.messaging.api.EnrichedMessage;
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;
import com.storagemanager.messaging.api.OutboxMessage;
import com.storagemanager.messaging.api.OutboxTransport;
import com.storagemanager.messaging.api.TransportResult;
import com.storagemanager.messaging.autoconfigure.MessagingAutoConfiguration;
import com.storagemanager.messaging.internal.InboxEntry;
import com.storagemanager.messaging.internal.InboxRepository;

import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.spring.JqwikSpringSupport;

/**
 * jqwik {@code @DataJpaTest} slice proving the design's
 * <b>Correctness Property 5 — Outcome mapping is a total function onto the processed_at stamp</b>
 * for the {@code inbox-action-dispatch} feature.
 *
 * <p><b>Feature: inbox-action-dispatch, Property 5: Outcome mapping is a total function onto the
 * processed_at stamp</b> — driving the <em>real</em> starter {@link IdempotentInbox#processOnce}
 * through the real {@link DispatchingHandler} maps every handler outcome deterministically onto the
 * inbox {@code processed_at} stamp:
 * <ul>
 *   <li><b>success</b> (handler returns an {@code ActionResult}) &rarr; {@code processOnce} returns
 *       normally, {@code processed_at} is stamped, and the handler/processor ran exactly once
 *       (Req 4.4, 5.1);</li>
 *   <li><b>terminal {@link ActionValidationException}</b> &rarr; the dispatcher records and returns
 *       normally, {@code processed_at} is stamped, and the handler ran exactly once (Req 5.3);</li>
 *   <li><b>reconstruction failure</b> (a corrupt required header) &rarr; the dispatcher records and
 *       returns normally, {@code processed_at} is stamped, and the handler is NOT invoked
 *       (count 0);</li>
 *   <li><b>unmatched eventType</b> &rarr; the dispatcher records and returns normally,
 *       {@code processed_at} is stamped, and the handler is NOT invoked (count 0) (Req 6.1);</li>
 *   <li><b>{@link ActionRetryableException}</b> &rarr; the dispatcher throws out of
 *       {@code processOnce}, {@code processed_at} is NOT stamped (null), and the message remains
 *       retry-eligible (Req 5.2).</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 4.4, 5.1, 5.2, 5.3, 6.1</b>
 *
 * <h2>What is exercised</h2>
 * This slice drives the <em>real</em> starter {@link IdempotentInbox} bean, {@code EnrichedMapper},
 * and {@link InboxRepository} wired by {@link MessagingAutoConfiguration} against a real database,
 * fed through the real {@link DispatchingHandler} + {@link HandlerRegistry} +
 * {@link EnvelopeReconstructor} (no mocking of inbox mechanics). The only stub is the one registered
 * {@link InboxHandler} ({@code eventType == "SendCommunication"}) whose outcome is configured per try
 * (success / terminal / retryable) and which counts its own invocations so the test can assert the
 * processor call count matches each outcome's expectation.
 *
 * <h2>Transaction semantics</h2>
 * {@code IdempotentInbox.processOnce} is {@code @Transactional(REQUIRED)}. The class is
 * {@code @Transactional(NOT_SUPPORTED)}, so each {@code processOnce} call runs with no ambient
 * transaction and manages its own committed/rolled-back unit — the same harness as
 * {@code InboxHandlerFailureRetryPropertyTest}. For the retryable outcome the thrown exception rolls
 * the self-managed transaction back, so {@code processed_at} is null (absent or unprocessed row).
 *
 * <h2>Harness</h2>
 * Mirrors {@code InboxHandlerFailureRetryPropertyTest}: H2 in PostgreSQL compatibility mode with the
 * {@code CREATE DOMAIN IF NOT EXISTS JSONB AS JSON} INIT clause so the enriched JSONB columns
 * materialize, {@code ddl-auto=create-drop}, inbox half on / outbox half off, scope-fields
 * {@code [organization_id, facility_id]}. Each try uses a fresh {@code messageId}, so no cross-try
 * cleanup is required.
 */
@JqwikSpringSupport
@DataJpaTest
@Import(OutcomeMappingTotalFunctionPropertyTest.TestBeans.class)
@ImportAutoConfiguration(MessagingAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
        // H2 in PostgreSQL mode with the JSONB domain alias registered via the INIT clause so the
        // enriched inbox JSONB payload/headers columns materialize.
        "spring.datasource.url=jdbc:h2:mem:comm_outcome_mapping;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "storagemanager.messaging.inbox.enabled=true",
        "storagemanager.messaging.outbox.enabled=false",
        "storagemanager.messaging.enrichment.enabled=true",
        "storagemanager.messaging.scope-fields[0]=organization_id",
        "storagemanager.messaging.scope-fields[1]=facility_id",
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutcomeMappingTotalFunctionPropertyTest {

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";
    private static final String MATCHED_EVENT_TYPE = "SendCommunication";
    private static final String UNMATCHED_EVENT_TYPE = "NoHandlerForThisEventType";

    /**
     * A configurable stub {@link InboxHandler} registered for {@code "SendCommunication"} that counts
     * its invocations and produces a chosen outcome (success / terminal / retryable). The real
     * {@link HandlerRegistry}, {@link EnvelopeReconstructor}, and {@link DispatchingHandler} are
     * assembled over it so the whole dispatch path is exercised unmocked. A disabled outbox means no
     * real {@link OutboxTransport} is needed; the stub is a harmless safety net.
     */
    @Configuration(proxyBeanMethods = false)
    static class TestBeans {

        static final CountingStubHandler STUB = new CountingStubHandler();

        @Bean
        DispatchingHandler dispatchingHandler() {
            HandlerRegistry registry = new HandlerRegistry(List.of(STUB));
            return new DispatchingHandler(registry, new EnvelopeReconstructor());
        }

        @Bean
        OutboxTransport stubTransport() {
            return new OutboxTransport() {
                @Override
                public TransportResult send(EnrichedMessage message) {
                    return TransportResult.success();
                }

                @Override
                public TransportResult send(OutboxMessage message) {
                    return TransportResult.success();
                }
            };
        }
    }

    /** The outcome a routed handler produces for a given try. */
    enum Outcome {
        SUCCESS,
        TERMINAL_VALIDATION,
        RETRYABLE,
        RECONSTRUCTION_FAILURE,
        UNMATCHED
    }

    /**
     * A single stub handler registered for {@code "SendCommunication"}. It counts invocations and,
     * per the currently configured outcome, either returns normally (success), throws
     * {@link ActionValidationException} (terminal), or throws {@link ActionRetryableException}
     * (retryable). RECONSTRUCTION_FAILURE and UNMATCHED never reach the handler, so its count stays 0.
     */
    static final class CountingStubHandler implements InboxHandler {

        private final AtomicInteger invocations = new AtomicInteger(0);
        private volatile Outcome outcome = Outcome.SUCCESS;

        void reset(Outcome next) {
            this.outcome = next;
            this.invocations.set(0);
        }

        int invocationCount() {
            return invocations.get();
        }

        @Override
        public String eventType() {
            return MATCHED_EVENT_TYPE;
        }

        @Override
        public void handle(EventEnvelope envelope) {
            invocations.incrementAndGet();
            switch (outcome) {
                case TERMINAL_VALIDATION -> throw new ActionValidationException("terminal defect");
                case RETRYABLE -> throw new ActionRetryableException("transient failure");
                default -> {
                    // SUCCESS: return normally (an ActionResult from a real processor; ignored here).
                }
            }
        }
    }

    @Autowired
    private IdempotentInbox inbox;

    @Autowired
    private InboxRepository inboxRepository;

    @Autowired
    private DispatchingHandler dispatchingHandler;

    /**
     * Property 5: for any of the five outcomes, driving the real {@code processOnce} through the real
     * {@link DispatchingHandler} maps the outcome deterministically onto {@code processed_at} and the
     * handler invocation count:
     * <ul>
     *   <li>SUCCESS / TERMINAL_VALIDATION &rarr; {@code processed_at} stamped, handler ran once;</li>
     *   <li>RECONSTRUCTION_FAILURE / UNMATCHED &rarr; {@code processed_at} stamped, handler ran 0 times;</li>
     *   <li>RETRYABLE &rarr; dispatcher throws, {@code processed_at} null, handler ran once.</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 4.4, 5.1, 5.2, 5.3, 6.1</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void outcomeMappingIsTotalOntoProcessedAtStamp(
            @ForAll("outcomes") Outcome outcome,
            @ForAll("uuids") UUID messageId,
            @ForAll("uuids") UUID organizationId,
            @ForAll("uuids") UUID facilityId,
            @ForAll("uuids") UUID correlationId) {

        TestBeans.STUB.reset(outcome);

        InboundMessage message =
                messageFor(outcome, messageId, organizationId, facilityId, correlationId);

        if (outcome == Outcome.RETRYABLE) {
            // Retryable is the ONLY outcome that escapes processOnce. The self-managed transaction
            // rolls back, so processed_at is null (absent or unprocessed row) and the handler ran once.
            assertThatThrownBy(() -> inbox.processOnce(message, dispatchingHandler))
                    .isInstanceOf(ActionRetryableException.class);

            InboxEntry afterThrow = inboxRepository.findByMessageId(messageId).orElse(null);
            if (afterThrow != null) {
                assertThat(afterThrow.getProcessedAt())
                        .as("retryable outcome must leave processed_at null (row unprocessed)")
                        .isNull();
            }
            assertThat(TestBeans.STUB.invocationCount())
                    .as("retryable outcome invokes the routed handler exactly once")
                    .isEqualTo(1);
            return;
        }

        // Every non-retryable outcome returns normally so processOnce stamps processed_at.
        inbox.processOnce(message, dispatchingHandler);

        InboxEntry row = inboxRepository.findByMessageId(messageId).orElseThrow();
        assertThat(row.getProcessedAt())
                .as("non-retryable outcome %s stamps processed_at (message consumed)", outcome)
                .isNotNull();

        int expectedHandlerCalls = switch (outcome) {
            case SUCCESS, TERMINAL_VALIDATION -> 1; // routed to and invoked the handler once
            case RECONSTRUCTION_FAILURE, UNMATCHED -> 0; // handler never invoked
            default -> throw new IllegalStateException("unreachable: " + outcome);
        };
        assertThat(TestBeans.STUB.invocationCount())
                .as("outcome %s invokes the routed handler %d time(s)", outcome, expectedHandlerCalls)
                .isEqualTo(expectedHandlerCalls);
    }

    /**
     * Build the InboundMessage for an outcome. SUCCESS/TERMINAL_VALIDATION/RETRYABLE use a valid
     * reconstructable message keyed to the matched eventType; RECONSTRUCTION_FAILURE corrupts a
     * required header (so the matched handler is never reached); UNMATCHED uses an eventType with no
     * registered handler.
     */
    private static InboundMessage messageFor(Outcome outcome, UUID messageId, UUID organizationId,
                                             UUID facilityId, UUID correlationId) {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put(ORG, organizationId);
        scope.put(FAC, facilityId);

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityKind", "LEAD");
        payload.put("leadId", UUID.randomUUID().toString());
        payload.put("templateName", "welcome");

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", java.time.Instant.now().toString());
        headers.put("entity_type", "Lead");
        headers.put("entity_id", UUID.randomUUID().toString());
        headers.put("actor_type", "SYSTEM");

        if (outcome == Outcome.RECONSTRUCTION_FAILURE) {
            // Corrupt a required header so EnvelopeReconstructor raises a terminal
            // ReconstructionException and the matched handler is never invoked (Req 3.6).
            headers.put("schema_version", "not-an-integer");
        }

        String eventType = outcome == Outcome.UNMATCHED ? UNMATCHED_EVENT_TYPE : MATCHED_EVENT_TYPE;

        return new InboundMessage(messageId, eventType, payload, scope, correlationId, headers);
    }

    @Provide
    net.jqwik.api.Arbitrary<Outcome> outcomes() {
        return net.jqwik.api.Arbitraries.of(
                Outcome.SUCCESS,
                Outcome.TERMINAL_VALIDATION,
                Outcome.RETRYABLE,
                Outcome.RECONSTRUCTION_FAILURE,
                Outcome.UNMATCHED);
    }

    @Provide
    net.jqwik.api.Arbitrary<UUID> uuids() {
        // A truly random UUID per draw so every try gets distinct identifiers and no idempotency key
        // collides across tries.
        return net.jqwik.api.Arbitraries.randomValue(random -> UUID.randomUUID());
    }
}
