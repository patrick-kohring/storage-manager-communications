package com.storagemanager.communications.messaging;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;
import com.storagemanager.messaging.autoconfigure.MessagingAutoConfiguration;
import com.storagemanager.messaging.internal.InboxRepository;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.spring.JqwikSpringSupport;

/**
 * jqwik property-based test proving the design's
 * <b>Correctness Property 2 — Concurrent redelivery is at-most-once</b> for the communications
 * messaging base.
 *
 * <p><b>Feature: messaging, Property 2: Concurrent redelivery is at-most-once</b> — for any
 * EventEnvelope delivered by N concurrent {@link IdempotentInbox#processOnce(InboundMessage,
 * java.util.function.Consumer)} invocations of the same {@code message_id}, the No_Op_Handler is
 * invoked for <em>at most one</em> of them and exactly one {@code inbox} row exists afterward. The
 * {@code uq_inbox_message_id} UNIQUE constraint is the concurrency guard: the losers of the insert
 * race return <em>without</em> running the handler (the winner owns processing), so the side effect
 * happens at most once.
 *
 * <p><b>Validates: Requirements 5.6, 5.7</b>
 *
 * <h2>Why a DB-backed concurrency property test (not a mocked one)</h2>
 * The at-most-once invariant under contention is enforced by the real
 * {@link IdempotentInbox#processOnce} path — insert-on-receive guarded by the real
 * {@code message_id} UNIQUE constraint, with the loser of the {@code DataIntegrityViolation} race
 * yielding without running the handler — backed by a real database and real independently-committing
 * transactions. So this property boots the H2 JPA slice, wires the <em>real</em> starter
 * {@link IdempotentInbox} bean via {@link MessagingAutoConfiguration} (so {@code processOnce} carries
 * its {@code @Transactional(REQUIRED)} proxy), and launches N threads with no mocking, mirroring the
 * starter's {@code IdempotentInboxPropertyTest} wiring and the communications
 * {@link OutboxTransactionalDataJpaTest} harness.
 *
 * <h2>Harness</h2>
 * <ul>
 *   <li>H2 in PostgreSQL compatibility mode with the {@code CREATE DOMAIN IF NOT EXISTS JSONB AS
 *       JSON} INIT clause (Req 2.10) so the enriched {@code payload}/{@code headers} {@code JSONB}
 *       columns materialize, and {@code DB_CLOSE_DELAY=-1} so every thread sees the same shared
 *       in-memory schema and the {@code message_id} UNIQUE constraint is enforced across threads on
 *       the same datasource.</li>
 *   <li>Hibernate creates the schema from the mapped starter entities ({@code ddl-auto=create-drop});
 *       the real {@code V2} DDL is covered on H2 by {@code V2MessagingMigrationSmokeTest}.</li>
 *   <li>Inbox half enabled with enrichment on and {@code scope-fields=[organization_id,
 *       facility_id]}; outbox half <em>disabled</em> so no {@code OutboxTransport} bean is required
 *       (the starter's fail-fast only applies when the outbox is enabled).</li>
 *   <li>{@code @DataJpaTest}'s auto-rolled-back managed transaction is disabled (class-level
 *       {@code @Transactional(NOT_SUPPORTED)}) so each thread's {@code processOnce} commits
 *       independently and the concurrent insert race is real; the table is cleared as the first
 *       action of each try so the absolute-count assertion is deterministic.</li>
 *   <li>DB-backed concurrency property tests are heavier, so tries are kept modest.</li>
 * </ul>
 */
@JqwikSpringSupport
@DataJpaTest
@ImportAutoConfiguration(MessagingAutoConfiguration.class)
// The starter's enriched inbox entity + repository and the IdempotentInbox bean are registered by
// MessagingAutoConfiguration's own @EntityScan/@EnableJpaRepositories + bean definitions, so they
// are intentionally NOT re-declared here. Communications has no host JPA entities this slice needs.
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
        // H2 in PostgreSQL mode with the JSONB domain alias registered via the INIT clause
        // (Req 2.10). DB_CLOSE_DELAY=-1 keeps the single shared in-memory database alive for the
        // duration of the test so all worker threads hit the same schema and the same
        // message_id UNIQUE constraint (the cross-thread concurrency guard).
        "spring.datasource.url=jdbc:h2:mem:comm_inbox_concurrent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // Create the schema from the mapped starter entities; the slice owns no Flyway migration.
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // Starter: inbox half with enrichment on, scope declared [organization_id, facility_id].
        // The outbox half is OFF so no OutboxTransport bean is required (the fail-fast applies
        // only when the outbox is enabled).
        "storagemanager.messaging.inbox.enabled=true",
        "storagemanager.messaging.outbox.enabled=false",
        "storagemanager.messaging.enrichment.enabled=true",
        "storagemanager.messaging.scope-fields[0]=organization_id",
        "storagemanager.messaging.scope-fields[1]=facility_id",
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InboxConcurrentAtMostOncePropertyTest {

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";

    @Autowired
    private IdempotentInbox inbox;

    @Autowired
    private InboxRepository inboxRepository;

    /**
     * A fully-enriched generic inbound message scoped to a fresh organization/facility, carrying a
     * correlation id and a non-empty header map so the enriched columns are populated.
     */
    private static InboundMessage genericInbound(UUID messageId) {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put(ORG, UUID.randomUUID());
        scope.put(FAC, UUID.randomUUID());

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityId", UUID.randomUUID().toString());
        payload.put("source", "TEST");

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("entity_type", "Generic");
        headers.put("actor_type", "SYSTEM");

        return new InboundMessage(messageId, "GenericEvent", payload, scope, UUID.randomUUID(), headers);
    }

    /**
     * Property 2: for N concurrent {@code processOnce} invocations of one {@code message_id}, the
     * No_Op_Handler runs for <strong>at most one</strong> of them (in practice exactly one, since
     * one insert wins) and <strong>exactly one</strong> inbox row exists for that {@code message_id}
     * afterward.
     *
     * <p>N worker threads are released simultaneously via a {@link CountDownLatch} to maximize
     * contention on the {@code message_id} UNIQUE constraint. The winner inserts the row and runs
     * the handler; every loser catches its insert-race {@code DataIntegrityViolation} internally
     * (the starter yields quietly, returning without running the handler) and therefore does not
     * increment the counter. Per the starter contract the loser returns quietly so no exception
     * should escape {@code processOnce}; a worker that nonetheless observes a thrown exception
     * treats it as "did not run handler" — the invariant to prove is at-most-one handler run plus
     * exactly-one row, so a thrown loser is only a failure if it somehow let the counter exceed 1.
     *
     * <p><b>Validates: Requirements 5.6, 5.7</b>
     */
    @Property(tries = 30)
    void concurrentRedeliveryRunsHandlerAtMostOnceAndLeavesExactlyOneRow(
            @ForAll @IntRange(min = 2, max = 8) int concurrency) throws InterruptedException {

        // Per-try isolation: clear the table first so the absolute-count assertion is deterministic.
        inboxRepository.deleteAll();

        // Fresh dedupe key per try so prior tries' rows never interfere.
        UUID messageId = UUID.randomUUID();
        InboundMessage message = genericInbound(messageId);

        AtomicInteger handlerInvocations = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        try {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        // All workers block here, then are released together to maximize the
                        // insert-race contention on the message_id UNIQUE constraint.
                        go.await();
                        inbox.processOnce(message, m -> handlerInvocations.incrementAndGet());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException raced) {
                        // Expected-but-not-required: per the starter contract the loser of the
                        // insert race returns quietly without running the handler. If some race
                        // instead propagates a DataIntegrityViolation / optimistic-lock exception,
                        // the handler definitively did NOT run on this worker, so we swallow it —
                        // the final counter/row assertions still enforce the invariant.
                    } finally {
                        done.countDown();
                    }
                });
            }

            // Release all workers at once once they are all parked at the gate.
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("all %d concurrent workers finished", concurrency)
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Invariant 1 (Req 5.6): the No_Op_Handler ran for AT MOST ONE of the N deliveries. In
        // practice exactly one, because one insert always wins the race and runs the handler.
        assertThat(handlerInvocations.get())
                .as("handler invoked at most once across %d concurrent deliveries", concurrency)
                .isLessThanOrEqualTo(1);
        assertThat(handlerInvocations.get())
                .as("exactly one winner ran the handler across %d concurrent deliveries", concurrency)
                .isEqualTo(1);

        // Invariant 2 (Req 5.7): exactly one inbox row exists for the message_id — the UNIQUE
        // constraint collapsed every losing insert.
        assertThat(inboxRepository.findByMessageId(messageId))
                .as("exactly one inbox row exists for the message_id")
                .isPresent();
        assertThat(inboxRepository.count())
                .as("exactly one inbox row exists in total (per-try isolation)")
                .isEqualTo(1L);
    }
}
