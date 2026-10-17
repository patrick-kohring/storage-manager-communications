package com.storagemanager.communications.messaging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.storagemanager.messaging.api.EnrichedMessage;
import com.storagemanager.messaging.api.OutboxMessage;
import com.storagemanager.messaging.api.OutboxTransport;
import com.storagemanager.messaging.api.OutboxWriter;
import com.storagemanager.messaging.api.TransportResult;
import com.storagemanager.messaging.autoconfigure.MessagingAutoConfiguration;
import com.storagemanager.messaging.internal.OutboxEntry;
import com.storagemanager.messaging.internal.OutboxRepository;

/**
 * {@code @DataJpaTest} slice proving the design's
 * <b>Correctness Property 4 — Atomic outbox write with per-{@code event_id} uniqueness</b> for the
 * communications messaging base.
 *
 * <p><b>Feature: messaging, Property 4: Atomic outbox write with per-event_id uniqueness</b> — for
 * any {@link EnrichedMessage} appended inside a caller transaction: if the transaction commits then
 * <em>exactly one</em> {@code outbox} row exists for that {@code event_id}, initialized unpublished
 * ({@code published_at} null) with {@code attempts = 0}; if it rolls back then <em>no</em>
 * {@code outbox} row exists; and appending the same {@code event_id} more than once yields
 * <em>exactly one</em> {@code outbox} row ({@code MANDATORY} propagation joins the caller tx; the
 * {@code uq_outbox_event_id} unique constraint dedupes).
 *
 * <p><b>Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 8.3</b>
 *
 * <h2>What is exercised</h2>
 * This slice drives the <em>real</em> starter {@link OutboxWriter}, {@code EnrichedMapper}, and
 * {@link OutboxRepository} wired by {@link MessagingAutoConfiguration} against a real database. The
 * append is driven through a {@link TransactionTemplate} so the test controls commit vs rollback
 * (the writer's {@code MANDATORY} propagation requires an active transaction). In this base scope
 * there is no domain trigger that appends to the outbox, so the slice appends a <em>generic</em>
 * {@link EnrichedMessage} directly — exactly the generic exercise the spec calls for.
 *
 * <h2>Harness</h2>
 * Mirrors the api reference pattern ({@code TransactionalOutboxDataJpaTest}) and the communications
 * module convention: H2 in PostgreSQL compatibility mode with the
 * {@code CREATE DOMAIN IF NOT EXISTS JSONB AS JSON} INIT clause (Req 2.10) so the enriched
 * {@code payload}/{@code headers} {@code JSONB} columns materialize, the auto-rolled-back managed
 * transaction disabled ({@code @Transactional(NOT_SUPPORTED)}), and every committed/rolled-back
 * unit of work driven through a {@link TransactionTemplate}. Hibernate creates the schema from the
 * starter entities ({@code ddl-auto=create-drop}) so the slice is self-contained — the enriched
 * {@code outbox}/{@code inbox} tables come from their mapped entities (the real {@code V2} DDL,
 * including its Postgres-only {@code gen_random_uuid()} default and partial indexes, is covered on
 * H2 by {@code V2MessagingMigrationSmokeTest}). The {@code outbox} table is cleared before each
 * test so the row-count assertions are deterministic.
 */
@DataJpaTest
@Import(OutboxTransactionalDataJpaTest.TestBeans.class)
@ImportAutoConfiguration(MessagingAutoConfiguration.class)
// The starter's enriched outbox/inbox entities + repositories are registered by
// MessagingAutoConfiguration's own @EntityScan/@EnableJpaRepositories, so they are intentionally
// NOT re-declared here. Communications has no host JPA entities this slice needs.
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
        // H2 in PostgreSQL mode with the JSONB domain alias registered via the INIT clause
        // (Req 2.10) so the enriched outbox/inbox JSONB payload/headers columns materialize.
        "spring.datasource.url=jdbc:h2:mem:comm_outbox;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // Create the schema from the mapped starter entities; the slice owns no Flyway migration.
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // Starter: outbox half with enrichment on, scope declared [organization_id, facility_id]
        // so scope_0 = organization_id and scope_1 = facility_id (Req 1.4, 1.5). Inbox half is
        // off for this produce-side slice.
        "storagemanager.messaging.outbox.enabled=true",
        "storagemanager.messaging.inbox.enabled=false",
        "storagemanager.messaging.enrichment.enabled=true",
        "storagemanager.messaging.scope-fields[0]=organization_id",
        "storagemanager.messaging.scope-fields[1]=facility_id",
        // Push the scheduled drain far out so the publisher never fires during this slice — we
        // assert the produce side only.
        "storagemanager.messaging.outbox.poll-interval-ms=3600000",
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxTransactionalDataJpaTest {

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";

    /**
     * The stub {@link OutboxTransport} the starter's {@code OutboxPublisher} depends on. The
     * publisher is not exercised here (this slice asserts the produce side only), but the bean
     * must exist so the context satisfies the starter's fail-fast outbox-transport requirement.
     */
    @Configuration(proxyBeanMethods = false)
    static class TestBeans {

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

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        // Deterministic per-test isolation: clear the outbox in a committed transaction.
        txTemplate.executeWithoutResult(status -> outboxRepository.deleteAll());
    }

    /**
     * A fully-enriched generic message scoped to the given organization/facility, carrying a
     * correlation id and a non-empty header map so the enriched columns are populated.
     */
    private static EnrichedMessage genericMessage(UUID eventId, UUID organizationId, UUID facilityId,
                                                  UUID correlationId) {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put(ORG, organizationId);
        scope.put(FAC, facilityId);

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityId", UUID.randomUUID().toString());
        payload.put("source", "TEST");

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("entity_type", "Generic");
        headers.put("actor_type", "SYSTEM");

        return new EnrichedMessage(eventId, "GenericEvent", payload, scope, correlationId, headers);
    }

    // Property 4 (commit arm): a committed append leaves exactly one outbox row for that event_id,
    // initialized unpublished (published_at null) with attempts = 0, and with the enriched
    // scope_0/scope_1/correlation_id/headers columns populated. (Req 6.1, 6.2, 6.5)
    @Test
    void onCommitLeavesExactlyOneUnpublishedOutboxRowWithPopulatedEnrichedColumns() {
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        // MANDATORY propagation: the append joins THIS caller transaction (Req 6.1).
        txTemplate.executeWithoutResult(status ->
                outboxWriter.append(genericMessage(eventId, organizationId, facilityId, correlationId)));

        // Exactly one outbox row after commit (Req 6.2).
        assertThat(outboxRepository.count()).as("exactly one outbox row after commit").isEqualTo(1L);

        OutboxEntry row = outboxRepository.findByEventId(eventId).orElseThrow();
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getEventType()).isEqualTo("GenericEvent");

        // Initialized unpublished with attempts = 0 (Req 6.5).
        assertThat(row.getPublishedAt()).as("row is unpublished (published_at null)").isNull();
        assertThat(row.getAttempts()).as("row initialized with attempts = 0").isZero();

        // Enriched columns populated: scope_0 = organization_id, scope_1 = facility_id (positional
        // binding, Req 1.5), the correlation id, and the header map round-tripped.
        assertThat(row.getScopeSlot(0)).as("scope_0 holds organization_id").isEqualTo(organizationId);
        assertThat(row.getScopeSlot(1)).as("scope_1 holds facility_id").isEqualTo(facilityId);
        assertThat(row.getCorrelationId()).as("correlation_id populated").isEqualTo(correlationId);
        assertThat(row.getHeaders())
                .as("headers carry the enriched metadata byte-for-byte")
                .containsEntry("schema_version", "1")
                .containsEntry("entity_type", "Generic")
                .containsEntry("actor_type", "SYSTEM");
    }

    // Property 4 (rollback arm): if the transaction rolls back after append (forced exception),
    // no outbox row remains — the MANDATORY append joined the same transaction, so the insert is
    // discarded with it. (Req 6.3)
    @Test
    void onRollbackLeavesNoOutboxRow() {
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();

        RuntimeException boom = new RuntimeException("forced rollback after append");
        try {
            txTemplate.executeWithoutResult(status -> {
                outboxWriter.append(genericMessage(eventId, organizationId, facilityId, UUID.randomUUID()));
                // Force the single transaction to roll back AFTER the outbox append.
                throw boom;
            });
        } catch (RuntimeException thrown) {
            assertThat(thrown).isSameAs(boom);
        }

        // Atomicity: the rollback discarded the appended outbox row (Req 6.3).
        assertThat(outboxRepository.count()).as("no outbox row after rollback").isZero();
        assertThat(outboxRepository.findByEventId(eventId)).as("no row for the rolled-back event_id").isEmpty();
    }

    // Property 4 (dedupe arm): appending the SAME event_id twice yields exactly one outbox row —
    // the uq_outbox_event_id unique constraint dedupes on event_id. (Req 6.4, 8.3)
    @Test
    void appendingSameEventIdTwiceYieldsExactlyOneOutboxRow() {
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();

        // First committed append -> one row.
        txTemplate.executeWithoutResult(status ->
                outboxWriter.append(genericMessage(eventId, organizationId, facilityId, UUID.randomUUID())));
        assertThat(outboxRepository.count()).as("one row after first append").isEqualTo(1L);

        // Second append of the SAME event_id (in its own committed tx) collapses on the unique
        // constraint -> still exactly one row.
        txTemplate.executeWithoutResult(status ->
                outboxWriter.append(genericMessage(eventId, organizationId, facilityId, UUID.randomUUID())));

        assertThat(outboxRepository.count())
                .as("dedupe on event_id collapses the duplicate to one row")
                .isEqualTo(1L);
        List<OutboxEntry> all = outboxRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getEventId()).isEqualTo(eventId);
    }
}
