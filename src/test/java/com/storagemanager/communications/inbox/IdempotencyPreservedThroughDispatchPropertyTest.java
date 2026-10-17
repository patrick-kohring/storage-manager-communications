package com.storagemanager.communications.inbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;
import com.storagemanager.messaging.autoconfigure.MessagingAutoConfiguration;
import com.storagemanager.messaging.internal.InboxEntry;
import com.storagemanager.messaging.internal.InboxRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.spring.JqwikSpringSupport;

/**
 * jqwik {@code @DataJpaTest} slice proving the inbox-action-dispatch design's
 * <b>Correctness Property 6 — Idempotency and dedupe are preserved through dispatch</b> for the
 * {@code storage-manager-communications} service (Task 9.2).
 *
 * <p><b>Feature: inbox-action-dispatch, Property 6: Idempotency and dedupe are preserved through
 * dispatch</b> — for any admitted message routed through the {@link DispatchingHandler} and <em>for
 * any</em> redelivery count {@code N >= 1} of the same {@code messageId}, the routed
 * {@link InboxHandler} is invoked <em>exactly once</em>, exactly one {@code inbox} row exists for
 * that {@code message_id} (= {@code eventId}), and its {@code processed_at} is non-null after the
 * first successful handler run. The {@link DispatchingHandler} preserves the Idempotent_Inbox
 * uniqueness, dedupe, insert-on-receive, and {@code processed_at}-stamping guarantees owned by the
 * {@code messaging} starter and leaves the {@code message_id} keying unchanged (Req 5.6): the
 * dispatcher completes normally on success, so {@code processOnce} stamps {@code processed_at} on
 * the first delivery and every subsequent delivery of the same key dedupe-skips the handler
 * (Req 8.4).
 *
 * <p><b>Validates: Requirements 5.6, 8.4</b>
 *
 * <h2>What is exercised</h2>
 * This slice mirrors {@code InboxExactlyOncePropertyTest} exactly (the real-inbox H2-PostgreSQL
 * slice, the JSONB domain alias, {@code @ImportAutoConfiguration(MessagingAutoConfiguration.class)},
 * {@code @Transactional(NOT_SUPPORTED)}), but replaces the inline no-op handler with the real
 * production pipeline this spec owns: a {@link DispatchingHandler} built over a
 * {@link HandlerRegistry} containing a <em>counting</em> {@link InboxHandler} registered for a known
 * {@code eventType}, plus a real {@link EnvelopeReconstructor}. Delivering the SAME {@code messageId}
 * {@code N} times through {@link IdempotentInbox#processOnce(InboundMessage, java.util.function.Consumer)}
 * drives insert-on-receive + stamp on the first delivery and a dedupe-skip on every redelivery, so
 * the counting handler runs exactly once and exactly one stamped inbox row remains — proving the
 * dedupe/idempotency guarantees survive dispatch unaltered.
 *
 * <h2>Harness</h2>
 * Identical to {@code InboxExactlyOncePropertyTest}: H2 in PostgreSQL compatibility mode with the
 * {@code CREATE DOMAIN IF NOT EXISTS JSONB AS JSON} INIT clause so the enriched {@code payload}/
 * {@code headers} {@code JSONB} columns materialize; {@code ddl-auto=create-drop}; the inbox half
 * enabled with enrichment and {@code scope-fields=[organization_id, facility_id]}, the outbox half
 * off; the auto-rolled-back managed transaction disabled ({@code @Transactional(NOT_SUPPORTED)}) so
 * each {@code processOnce} manages its own committed transaction; and the {@code inbox} table cleared
 * at the start of each try (committed) so the absolute-count assertions are deterministic. The
 * {@link DispatchingHandler} is constructed by hand inside the property (no Spring wiring of the
 * inbox beans is needed), driven with the real starter {@link IdempotentInbox}/{@link InboxRepository}.
 */
@JqwikSpringSupport
@DataJpaTest
@ImportAutoConfiguration(MessagingAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
        // H2 in PostgreSQL mode with the JSONB domain alias registered via the INIT clause so the
        // enriched inbox JSONB payload/headers columns materialize.
        "spring.datasource.url=jdbc:h2:mem:comm_inbox_dispatch_idem;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // Create the schema from the mapped starter entities; the slice owns no Flyway migration.
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // Starter: inbox half with enrichment on, scope declared [organization_id, facility_id] so
        // scope_0 = organization_id and scope_1 = facility_id. Outbox half is OFF for this
        // inbox-focused slice, so no OutboxTransport bean is required.
        "storagemanager.messaging.inbox.enabled=true",
        "storagemanager.messaging.outbox.enabled=false",
        "storagemanager.messaging.enrichment.enabled=true",
        "storagemanager.messaging.scope-fields[0]=organization_id",
        "storagemanager.messaging.scope-fields[1]=facility_id",
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotencyPreservedThroughDispatchPropertyTest {

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";

    /** The eventType the counting handler is registered for (the routed, matched handler). */
    private static final String MATCHED_EVENT_TYPE = "SendCommunication";

    @Autowired
    private IdempotentInbox inbox;

    @Autowired
    private InboxRepository inboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    private TransactionTemplate txTemplate() {
        if (txTemplate == null) {
            txTemplate = new TransactionTemplate(transactionManager);
        }
        return txTemplate;
    }

    /**
     * Property 6: for any valid envelope matched to a registered handler and any redelivery count
     * {@code n >= 1} of the same {@code messageId}, driving the real
     * {@link IdempotentInbox#processOnce(InboundMessage, java.util.function.Consumer)} with the
     * production {@link DispatchingHandler} {@code n} times yields:
     * <ul>
     *   <li>the routed {@link InboxHandler} invoked <b>exactly once</b> across all {@code n}
     *       deliveries (dedupe preserved through dispatch — Req 8.4): the first delivery inserts and
     *       runs the handler; every duplicate dedupe-skips because {@code processed_at} is already
     *       stamped;</li>
     *   <li>exactly one {@code inbox} row for the {@code message_id} (= {@code eventId}) with a
     *       non-null {@code processed_at} after the first successful run (Req 5.6).</li>
     * </ul>
     * The {@link DispatchingHandler} completes normally on success, so the Idempotent_Inbox stamping
     * and dedupe rules are preserved unaltered (Req 5.6).
     *
     * <p><b>Validates: Requirements 5.6, 8.4</b>
     */
    @Property(tries = 100)
    void redeliveriesThroughDispatchRunHandlerExactlyOnceAndLeaveOneProcessedRow(
            @ForAll("envelopes") EventEnvelope envelope,
            @ForAll @IntRange(min = 1, max = 5) int redeliveries) {

        // Per-try isolation: clear the inbox in a committed transaction so the absolute-count and
        // processed_at assertions below are deterministic. A fresh random eventId per try also
        // guarantees no cross-try interference.
        txTemplate().executeWithoutResult(status -> inboxRepository.deleteAll());

        // Build the REAL dispatch pipeline: a counting matched handler registered for the known
        // eventType, a real HandlerRegistry, and a real EnvelopeReconstructor.
        AtomicInteger handlerInvocations = new AtomicInteger(0);
        InboxHandler countingHandler = new InboxHandler() {
            @Override
            public String eventType() {
                return MATCHED_EVENT_TYPE;
            }

            @Override
            public void handle(EventEnvelope routed) {
                handlerInvocations.incrementAndGet();
                // succeeds (returns normally)
            }
        };
        HandlerRegistry registry = new HandlerRegistry(List.of(countingHandler));
        DispatchingHandler dispatchingHandler =
                new DispatchingHandler(registry, new EnvelopeReconstructor());

        InboundMessage message = toInboundMessage(envelope);
        UUID messageId = envelope.eventId();

        // Deliver the SAME message_id n times through the real dispatcher. processOnce is a
        // @Transactional(REQUIRED) Spring bean, so each call manages its own committed transaction:
        // the first delivery inserts + runs the dispatcher (which routes to the counting handler) +
        // stamps processed_at; every redelivery is a dedupe-skip (the dispatcher is never invoked).
        for (int i = 0; i < redeliveries; i++) {
            inbox.processOnce(message, dispatchingHandler);
        }

        // Dedupe preserved through dispatch: the routed handler runs exactly once across all n
        // deliveries (Req 8.4).
        assertThat(handlerInvocations.get())
                .as("the routed handler runs exactly once across %d deliveries of the same messageId "
                        + "(dedupe preserved through dispatch)", redeliveries)
                .isEqualTo(1);

        // Exactly one inbox row for this message_id, stamped processed (Req 5.6).
        InboxEntry row = txTemplate().execute(status ->
                inboxRepository.findByMessageId(messageId).orElse(null));
        assertThat(row)
                .as("exactly one inbox row exists for the message_id (= eventId)")
                .isNotNull();
        assertThat(row.getMessageId())
                .as("message_id equals the envelope eventId (preserved idempotency key, unaltered)")
                .isEqualTo(messageId);
        assertThat(row.getProcessedAt())
                .as("processed_at is non-null after the first successful dispatch (Req 5.6)")
                .isNotNull();

        // Exactly one row in total for this isolated try — at most one inbox row per message_id
        // (Req 5.6 uniqueness preserved through dispatch).
        long totalRows = txTemplate().execute(status -> inboxRepository.count());
        assertThat(totalRows)
                .as("exactly one inbox row exists in total (per-try isolation)")
                .isEqualTo(1L);
    }

    /**
     * Map an {@link EventEnvelope} onto the starter {@link InboundMessage} the same way
     * {@code InboundRecordingService} does: {@code messageId = eventId}; scope
     * {@code organization_id -> scope_0}, {@code facility_id -> scope_1} (nullable, no sentinel);
     * the seven non-promoted metadata fields as passthrough headers.
     */
    private static InboundMessage toInboundMessage(EventEnvelope e) {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put(ORG, e.organizationId());
        scope.put(FAC, e.facilityId()); // null -> scope_1 null

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", Integer.toString(e.schemaVersion()));
        headers.put("occurred_at", e.occurredAt().toString());
        headers.put("entity_type", e.entityType());
        headers.put("entity_id", e.entityId().toString());
        headers.put("actor_type", e.actorType().name());
        if (e.actorId() != null) {
            headers.put("actor_id", e.actorId().toString());
        }
        if (e.causationId() != null) {
            headers.put("causation_id", e.causationId().toString());
        }

        return new InboundMessage(
                e.eventId(),
                e.eventType(),
                e.payload(),
                scope,
                e.correlationId(),
                headers);
    }

    /**
     * Arbitrary valid envelopes matched to the registered handler, with a <b>fresh random
     * {@code eventId} per generated value</b> (so each try keys on its own {@code message_id}). The
     * {@code eventType} is fixed to {@link #MATCHED_EVENT_TYPE} so the dispatcher always routes to the
     * counting handler (and so reconstruction always succeeds — all required headers/scope present).
     * The organization scope is always present; the facility scope is optionally null; the optional
     * {@code actorId}/{@code causationId} are optionally null; the payload is a small generic map.
     */
    @Provide
    Arbitrary<EventEnvelope> envelopes() {
        Arbitrary<EventEnvelope.ActorType> actorTypes =
                Arbitraries.of(EventEnvelope.ActorType.SYSTEM, EventEnvelope.ActorType.USER);
        Arbitrary<Integer> schemaVersions = Arbitraries.integers().between(1, 9);

        return Arbitraries.of(Boolean.TRUE, Boolean.FALSE).flatMap(withFacility ->
                actorTypes.flatMap(actorType ->
                        schemaVersions.map(schemaVersion -> {
                            UUID facilityId = withFacility ? UUID.randomUUID() : null;
                            UUID actorId = actorType == EventEnvelope.ActorType.USER
                                    ? UUID.randomUUID() : null;
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("entityRef", UUID.randomUUID().toString());
                            payload.put("source", "TEST");
                            return new EventEnvelope(
                                    UUID.randomUUID(),      // fresh eventId per value
                                    MATCHED_EVENT_TYPE,     // always routes to the counting handler
                                    schemaVersion,
                                    java.time.Instant.now(),
                                    UUID.randomUUID(),      // organizationId
                                    facilityId,
                                    "Generic",              // entityType
                                    UUID.randomUUID(),      // entityId
                                    actorType,
                                    actorId,
                                    UUID.randomUUID(),      // correlationId
                                    null,                   // causationId
                                    payload);
                        })));
    }
}
