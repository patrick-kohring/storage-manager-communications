package com.storagemanager.communications.messaging;

import java.util.LinkedHashMap;
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
 * jqwik {@code @DataJpaTest} slice proving the design's
 * <b>Correctness Property 1 — Inbox exactly-once no-op lifecycle under redelivery</b> for the
 * communications messaging base.
 *
 * <p><b>Feature: messaging, Property 1: Inbox exactly-once no-op lifecycle under redelivery</b> —
 * for any {@link EventEnvelope} and <em>for any</em> redelivery count {@code N >= 1} of the same
 * {@code eventId}, the {@code No_Op_Handler} is invoked <em>exactly once</em>, exactly one
 * {@code inbox} row exists for that {@code message_id} (= {@code eventId}), its
 * {@code processed_at} is non-null after the first successful handler run, and every delivery is
 * accepted idempotently (the first delivery records; every duplicate dedupe-skips and returns
 * without a second side effect — the inbox-level analogue of the endpoint returning {@code 200}
 * for both the new and every duplicate message).
 *
 * <p><b>Validates: Requirements 3.6, 5.1, 5.2, 5.3, 5.4, 8.1, 8.2</b>
 *
 * <h2>What is exercised</h2>
 * This slice drives the <em>real</em> starter {@link IdempotentInbox} bean (the enriched
 * {@code processOnce(InboundMessage, Consumer)} overload), {@code EnrichedMapper}, and
 * {@link InboxRepository} wired by {@link MessagingAutoConfiguration} against a real database — no
 * mocking. The handler is a counting pass-through {@code Consumer<InboundMessage>} standing in for
 * the production {@code No_Op_Handler}: it increments an {@link AtomicInteger} so the test can
 * assert the handler runs exactly once across {@code N} deliveries of the same {@code message_id}.
 * The envelope-to-{@link InboundMessage} mapping mirrors {@code InboundRecordingService}
 * ({@code messageId = eventId}, {@code organization_id -> scope_0}, {@code facility_id -> scope_1},
 * the seven metadata fields as passthrough headers), so the mapped {@code message_id} is the
 * preserved Idempotency_Key (Req 5.1, 8.2) and the dedupe is keyed on it (Req 8.1).
 *
 * <h2>Harness</h2>
 * Mirrors {@code OutboxTransactionalDataJpaTest} and the module convention: H2 in PostgreSQL
 * compatibility mode with the {@code CREATE DOMAIN IF NOT EXISTS JSONB AS JSON} INIT clause
 * (Req 2.10) so the enriched {@code payload}/{@code headers} {@code JSONB} columns materialize;
 * {@code ddl-auto=create-drop} so the schema comes from the mapped starter entities; the
 * auto-rolled-back managed transaction disabled ({@code @Transactional(NOT_SUPPORTED)}); and the
 * {@code inbox} table cleared at the start of each try (in a committed transaction) so the
 * absolute-count assertions are deterministic. This is an <b>inbox-focused</b> slice, so the
 * inbox half is enabled and the outbox half is <b>off</b> — no {@code OutboxTransport} bean is
 * needed and the starter's fail-fast outbox-transport requirement does not apply.
 *
 * <p>{@code @JqwikSpringSupport} bridges jqwik's lifecycle to the Spring {@code TestContext} so the
 * autowired {@link IdempotentInbox}/{@link InboxRepository} beans are available inside the
 * {@code @Property} method, mirroring the starter's own {@code IdempotentInboxPropertyTest} wiring.
 * The autoconfigured {@link IdempotentInbox} is a Spring bean, so its
 * {@code @Transactional(REQUIRED)} proxy means each {@code processOnce} call manages its own
 * transaction — the test calls it directly without wrapping it in a {@link TransactionTemplate}
 * (the template is used only for the committed clear/read-backs).
 *
 * <p>A fresh random {@code eventId} is drawn per try, so there is no cross-try interference and
 * each try asserts only against its own {@code message_id}. Tries are set to {@code 100} per the
 * design's minimum; the per-try table clear keeps the DB-backed cost bounded.
 */
@JqwikSpringSupport
@DataJpaTest
@ImportAutoConfiguration(MessagingAutoConfiguration.class)
// The starter's enriched inbox entity + repository are registered by MessagingAutoConfiguration's
// own @EntityScan/@EnableJpaRepositories, so they are intentionally NOT re-declared here.
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
        // H2 in PostgreSQL mode with the JSONB domain alias registered via the INIT clause
        // (Req 2.10) so the enriched inbox JSONB payload/headers columns materialize.
        "spring.datasource.url=jdbc:h2:mem:comm_inbox;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // Create the schema from the mapped starter entities; the slice owns no Flyway migration.
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // Starter: inbox half with enrichment on, scope declared [organization_id, facility_id] so
        // scope_0 = organization_id and scope_1 = facility_id (Req 1.4, 1.5). Outbox half is OFF
        // for this inbox-focused slice, so no OutboxTransport bean is required.
        "storagemanager.messaging.inbox.enabled=true",
        "storagemanager.messaging.outbox.enabled=false",
        "storagemanager.messaging.enrichment.enabled=true",
        "storagemanager.messaging.scope-fields[0]=organization_id",
        "storagemanager.messaging.scope-fields[1]=facility_id",
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InboxExactlyOncePropertyTest {

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";

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
     * Property 1: for any envelope identifiers and any redelivery count {@code n >= 1} of the same
     * {@code eventId}, driving the real {@link IdempotentInbox#processOnce(InboundMessage,
     * java.util.function.Consumer)} with a counting handler {@code n} times yields:
     * <ul>
     *   <li>the handler invoked <b>exactly once</b> (Req 3.6, 5.3, 8.1) — the first delivery
     *       inserts-on-receive and runs the handler (Req 5.2); every duplicate dedupe-skips
     *       because {@code processed_at} is already stamped (Req 5.3);</li>
     *   <li>exactly one {@code inbox} row for the {@code message_id} (= {@code eventId}) with a
     *       non-null {@code processed_at} after the first successful run (Req 5.4, 8.2); and</li>
     *   <li>the {@code message_id} equal to the envelope {@code eventId} — the preserved
     *       Idempotency_Key dedupe key (Req 5.1, 8.1, 8.2).</li>
     * </ul>
     * Every delivery (first and each duplicate) completes normally without throwing, the
     * inbox-level analogue of the endpoint accepting both the new and every duplicate message
     * (Req 3.6).
     *
     * <p><b>Validates: Requirements 3.6, 5.1, 5.2, 5.3, 5.4, 8.1, 8.2</b>
     */
    @Property(tries = 100)
    void redeliveriesOfSameEventIdRunHandlerExactlyOnceAndLeaveOneProcessedRow(
            @ForAll("envelopes") EventEnvelope envelope,
            @ForAll @IntRange(min = 1, max = 8) int redeliveries) {

        // Per-try isolation: clear the inbox first in a committed transaction so the absolute-count
        // and processed_at assertions below are deterministic. A fresh random eventId per try (see
        // the envelopes provider) also guarantees no cross-try interference.
        txTemplate().executeWithoutResult(status -> inboxRepository.deleteAll());

        AtomicInteger handlerInvocations = new AtomicInteger(0);
        InboundMessage message = toInboundMessage(envelope);
        UUID messageId = envelope.eventId();

        // Deliver the SAME message_id n times. The real processOnce is a @Transactional(REQUIRED)
        // Spring bean, so each call manages its own committed transaction: first delivery
        // inserts + runs the handler + stamps processed_at; every redelivery is a dedupe-skip.
        for (int i = 0; i < redeliveries; i++) {
            inbox.processOnce(message, m -> handlerInvocations.incrementAndGet());
        }

        // Exactly-once side effect across all n deliveries (Req 3.6, 5.3, 8.1).
        assertThat(handlerInvocations.get())
                .as("No_Op_Handler runs exactly once across %d deliveries of the same eventId",
                        redeliveries)
                .isEqualTo(1);

        // Exactly one inbox row for this message_id, stamped processed (Req 5.4, 8.2).
        InboxEntry row = txTemplate().execute(status ->
                inboxRepository.findByMessageId(messageId).orElse(null));
        assertThat(row)
                .as("exactly one inbox row exists for the message_id (= eventId)")
                .isNotNull();
        assertThat(row.getMessageId())
                .as("message_id equals the envelope eventId (preserved Idempotency_Key)")
                .isEqualTo(messageId);
        assertThat(row.getProcessedAt())
                .as("processed_at is non-null after the first successful handler run")
                .isNotNull();
        assertThat(row.getScopeSlot(0))
                .as("scope_0 holds the organization_id (positional binding)")
                .isEqualTo(envelope.organizationId());
        assertThat(row.getScopeSlot(1))
                .as("scope_1 holds the facility_id (null preserved, no sentinel)")
                .isEqualTo(envelope.facilityId());

        // Exactly one row in total for this isolated try — at most one inbox row per message_id
        // (Req 5.4 uniqueness, Req 8.2).
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
        scope.put(FAC, e.facilityId()); // null -> scope_1 null (Req 9.3)

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
     * Arbitrary valid envelopes with a <b>fresh random {@code eventId} per generated value</b> (so
     * each try keys on its own {@code message_id}). The organization scope is always present; the
     * facility scope is optionally null (exercising the no-sentinel {@code scope_1} path); the
     * optional {@code actorId}/{@code causationId} are optionally null; the payload is a small
     * generic identifier map (the starter never interprets it).
     */
    @Provide
    Arbitrary<EventEnvelope> envelopes() {
        Arbitrary<String> eventTypes = Arbitraries.of(
                "GenericEvent", "SendCommunication", "LeadCreated", "SomethingHappened", "X");
        Arbitrary<EventEnvelope.ActorType> actorTypes =
                Arbitraries.of(EventEnvelope.ActorType.SYSTEM, EventEnvelope.ActorType.USER);
        Arbitrary<Integer> schemaVersions = Arbitraries.integers().between(1, 9);

        return Arbitraries.of(Boolean.TRUE, Boolean.FALSE).flatMap(withFacility ->
                actorTypes.flatMap(actorType ->
                        eventTypes.flatMap(eventType ->
                                schemaVersions.map(schemaVersion -> {
                                    UUID facilityId = withFacility ? UUID.randomUUID() : null;
                                    UUID actorId = actorType == EventEnvelope.ActorType.USER
                                            ? UUID.randomUUID() : null;
                                    Map<String, Object> payload = new LinkedHashMap<>();
                                    payload.put("entityRef", UUID.randomUUID().toString());
                                    payload.put("source", "TEST");
                                    return new EventEnvelope(
                                            UUID.randomUUID(),      // fresh eventId per value
                                            eventType,
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
                                }))));
    }
}
