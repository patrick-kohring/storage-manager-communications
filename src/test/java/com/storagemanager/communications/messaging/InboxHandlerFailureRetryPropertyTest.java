package com.storagemanager.communications.messaging;

import java.util.LinkedHashMap;
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
import net.jqwik.spring.JqwikSpringSupport;

/**
 * {@code @DataJpaTest} slice proving the design's
 * <b>Correctness Property 3 — Handler failure leaves the row retry-eligible</b> for the
 * communications messaging base.
 *
 * <p><b>Feature: messaging, Property 3: Handler failure leaves the row retry-eligible</b> — for any
 * envelope, if the handler raises during {@code processOnce} then {@code processed_at} is NOT
 * stamped (the message is not consumed), and a subsequent {@code processOnce} with a succeeding
 * handler stamps {@code processed_at} and runs the handler. The first failed attempt did not
 * consume the idempotency slot, so the message remains eligible for retry.
 *
 * <p><b>Validates: Requirements 5.5</b>
 *
 * <h2>What is exercised</h2>
 * This slice drives the <em>real</em> starter {@link IdempotentInbox} (enriched
 * {@link IdempotentInbox#processOnce(InboundMessage, java.util.function.Consumer)} overload),
 * {@code EnrichedMapper}, and {@link InboxRepository} wired by {@link MessagingAutoConfiguration}
 * against a real database.
 *
 * <h2>Transaction semantics (the crux of this property)</h2>
 * {@code IdempotentInbox.processOnce} is {@code @Transactional(REQUIRED)}. The test class is
 * {@code @Transactional(NOT_SUPPORTED)}, which disables the auto-rolled-back managed test
 * transaction, so each {@code processOnce} call executes with <em>no ambient transaction</em> and
 * therefore <em>manages its own</em> committed/rolled-back unit of work. When the handler throws,
 * the self-managed transaction rolls back, discarding the just-inserted received row — so after a
 * failed attempt either no committed row exists OR an unprocessed row exists; in both cases
 * {@code processed_at} is null (the message is NOT marked processed). A subsequent call with a
 * succeeding handler then re-inserts/reuses the row, runs the handler, and stamps
 * {@code processed_at} — exactly the retry-eligible behavior (Req 5.5; see the
 * {@code IdempotentInbox} "Handler failure" javadoc).
 *
 * <h2>Harness</h2>
 * Mirrors {@link OutboxTransactionalDataJpaTest} and the starter's inbox tests: H2 in PostgreSQL
 * compatibility mode with the {@code CREATE DOMAIN IF NOT EXISTS JSONB AS JSON} INIT clause
 * (Req 2.10) so the enriched {@code payload}/{@code headers} {@code JSONB} columns materialize,
 * {@code ddl-auto=create-drop} to build the schema from the starter entities, the inbox half
 * enabled with enrichment on and {@code scope-fields=[organization_id, facility_id]}, and the
 * outbox half disabled. {@code @JqwikSpringSupport} lets the {@code @Property} methods receive the
 * {@code @Autowired} starter beans. Every try uses a fresh {@code messageId}, so no cross-try
 * cleanup is required.
 */
@JqwikSpringSupport
@DataJpaTest
@Import(InboxHandlerFailureRetryPropertyTest.TestBeans.class)
@ImportAutoConfiguration(MessagingAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
        // H2 in PostgreSQL mode with the JSONB domain alias registered via the INIT clause
        // (Req 2.10) so the enriched inbox JSONB payload/headers columns materialize.
        "spring.datasource.url=jdbc:h2:mem:comm_inbox_retry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // Create the schema from the mapped starter entities; the slice owns no Flyway migration.
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // Starter: inbox half with enrichment on, scope declared [organization_id, facility_id]
        // so scope_0 = organization_id and scope_1 = facility_id (Req 1.4, 1.5). Outbox half is
        // off for this consume-side slice.
        "storagemanager.messaging.inbox.enabled=true",
        "storagemanager.messaging.outbox.enabled=false",
        "storagemanager.messaging.enrichment.enabled=true",
        "storagemanager.messaging.scope-fields[0]=organization_id",
        "storagemanager.messaging.scope-fields[1]=facility_id",
})
// Disable the managed (auto-rolled-back) test transaction so each processOnce is its own
// committed/rolled-back unit — the crux of exercising the handler-failure rollback (Req 5.5).
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InboxHandlerFailureRetryPropertyTest {

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";

    /**
     * The inbox half does not depend on an {@link OutboxTransport}, but the starter autoconfig may
     * still reference one; a disabled outbox means none is required. This stub is a harmless safety
     * net kept consistent with {@link OutboxTransactionalDataJpaTest} and is unused here.
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
    private IdempotentInbox inbox;

    @Autowired
    private InboxRepository inboxRepository;

    /**
     * A generic enriched inbound message scoped to the given organization/facility, carrying a
     * correlation id and a non-empty header map so the enriched columns are populated.
     */
    private static InboundMessage genericMessage(UUID messageId, UUID organizationId, UUID facilityId,
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

        return new InboundMessage(messageId, "GenericEvent", payload, scope, correlationId, headers);
    }

    // Property 3: a handler that throws during processOnce leaves the row NOT marked processed
    // (processed_at null, whether rolled back to absent or persisted unprocessed), and a subsequent
    // processOnce with a succeeding handler runs the handler exactly once and stamps processed_at —
    // the failed attempt did not consume the idempotency slot (Req 5.5).
    //
    // tries = 100, RANDOMIZED: DB-backed but each try is a tiny insert/rollback + insert/stamp on
    // in-memory H2, so 100 randomized fresh-messageId tries run fast. Each try draws a distinct
    // random UUID per field so no idempotency key is reused across tries.
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void handlerFailureLeavesRowRetryEligible(
            @ForAll("uuids") UUID messageId,
            @ForAll("uuids") UUID organizationId,
            @ForAll("uuids") UUID facilityId,
            @ForAll("uuids") UUID correlationId) {

        InboundMessage message = genericMessage(messageId, organizationId, facilityId, correlationId);

        // --- First attempt: the handler raises. processOnce manages its own tx (no ambient tx due
        // to NOT_SUPPORTED), so the thrown handler rolls that tx back and the inserted row with it.
        assertThatThrownBy(() ->
                inbox.processOnce(message, m -> {
                    throw new RuntimeException("boom");
                }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        // The message is NOT marked processed: either no committed row exists (rolled back) OR an
        // unprocessed row exists; in both cases processed_at is null (Req 5.5).
        InboxEntry afterFailure = inboxRepository.findByMessageId(messageId).orElse(null);
        if (afterFailure != null) {
            assertThat(afterFailure.getProcessedAt())
                    .as("a persisted row after handler failure must be unprocessed (processed_at null)")
                    .isNull();
        }

        // --- Second attempt: a succeeding handler. Because the first attempt did not consume the
        // idempotency slot, the handler runs now and processed_at is stamped (retry eligibility).
        AtomicInteger counter = new AtomicInteger(0);
        inbox.processOnce(message, m -> counter.incrementAndGet());

        assertThat(counter.get())
                .as("succeeding handler runs exactly once after the prior failure")
                .isEqualTo(1);

        InboxEntry afterSuccess = inboxRepository.findByMessageId(messageId).orElseThrow();
        assertThat(afterSuccess.getProcessedAt())
                .as("processed_at is stamped once the handler succeeds")
                .isNotNull();
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<UUID> uuids() {
        // A truly random UUID per draw. randomValue forces RANDOMIZED generation for this
        // arbitrary (no exhaustive single-value reuse), so every try gets a distinct identifier
        // and no idempotency key collides across tries.
        return net.jqwik.api.Arbitraries.randomValue(random -> UUID.randomUUID());
    }
}
