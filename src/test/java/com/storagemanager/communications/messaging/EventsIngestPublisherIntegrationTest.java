package com.storagemanager.communications.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagemanager.messaging.api.EnrichedMessage;
import com.storagemanager.messaging.api.OutboxTransport;
import com.storagemanager.messaging.api.OutboxWriter;
import com.storagemanager.messaging.autoconfigure.MessagingAutoConfiguration;
import com.storagemanager.messaging.internal.OutboxEntry;
import com.storagemanager.messaging.internal.OutboxPublisher;
import com.storagemanager.messaging.internal.OutboxRepository;

/**
 * Stubbed-events integration test for the outbox publish path of the communications messaging base
 * (Task 13.2).
 *
 * <p>This test exercises the <strong>real</strong> starter {@link OutboxWriter},
 * {@link OutboxPublisher}, {@link OutboxRepository}, and {@code EnrichedMapper} (wired by
 * {@link MessagingAutoConfiguration}) together with the host {@link EventsIngestTransport}, bound to
 * a {@link MockRestServiceServer} standing in for the events ingest endpoint at
 * {@code /api/v1/events}. Unlike the transport-only unit/property tests
 * ({@code EventsIngestTransportTest} / {@code EventsIngestTransportPropertyTest}, which hand the
 * transport an {@link EnrichedMessage} directly), this test drives the actual scheduled drain entry
 * point — {@link OutboxPublisher#drain()} invoked deterministically — so the end-to-end
 * append &rarr; select-due &rarr; transport &rarr; persist-outcome chain is covered.
 *
 * <h2>Assertion layers (per task 13.2)</h2>
 * <ul>
 *   <li><b>Transport/wire level</b> — the POSTed JSON is the canonical 13-field
 *       {@code EventEnvelope} with scope lifted positionally to {@code organizationId}/
 *       {@code facilityId}, the seven metadata headers lifted back, {@code eventId}/
 *       {@code eventType}/{@code payload} preserved, and {@code actorId}/{@code causationId} null
 *       when their headers are absent (captured by the {@link MockRestServiceServer} request
 *       matcher).</li>
 *   <li><b>Publisher level</b> — the persisted outbox row transitions correctly for each stubbed
 *       HTTP status: {@code 200} &rarr; {@code published_at} stamped and not re-selected on the next
 *       drain (Req 6.7); {@code 5xx} &rarr; {@code attempts++} with {@code next_attempt_at} advanced
 *       via backoff (Req 6.8); driven to {@code max-attempts} &rarr; parked, no longer selected
 *       (Req 6.9); {@code 400}/{@code 403} &rarr; parked immediately (Req 7.3 permanent); and a
 *       seeded mix of due/undue rows delivers only the due rows, capped at {@code batch-size}
 *       (Req 6.6).</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 6.6, 6.7, 6.8, 6.9, 7.1, 8.3</b>
 *
 * <h2>Harness</h2>
 * Mirrors the module's {@code OutboxTransactionalDataJpaTest} convention: H2 in PostgreSQL
 * compatibility mode with the {@code CREATE DOMAIN IF NOT EXISTS JSONB AS JSON} INIT clause so the
 * enriched {@code JSONB} {@code payload}/{@code headers} columns materialize, schema created from
 * the starter entities ({@code ddl-auto=create-drop}), and the managed per-test transaction
 * disabled ({@code @Transactional(NOT_SUPPORTED)}) so appends and {@code drain()} commit as their
 * own units of work and the row-state assertions observe committed state. The scheduled poll is
 * pushed far out so the only drains are the ones this test invokes directly. Backoff and
 * {@code max-attempts} are tightened so the retry/parking arcs are deterministic. The transport is
 * bound to a {@link MockRestServiceServer} so no real network call is ever made (fully hermetic).
 */
@DataJpaTest
@Import(EventsIngestPublisherIntegrationTest.StubbedTransportConfig.class)
@ImportAutoConfiguration(MessagingAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
        // Distinct in-memory H2 db name (hermetic isolation from other suite tests), PostgreSQL
        // mode + the JSONB domain alias so the enriched outbox/inbox JSONB columns materialize.
        "spring.datasource.url=jdbc:h2:mem:comm_publisher_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // Outbox half with enrichment on; scope declared [organization_id, facility_id] so
        // scope_0 = organization_id and scope_1 = facility_id (Req 1.4, 1.5). Inbox half off.
        "storagemanager.messaging.outbox.enabled=true",
        "storagemanager.messaging.inbox.enabled=false",
        "storagemanager.messaging.enrichment.enabled=true",
        "storagemanager.messaging.scope-fields[0]=organization_id",
        "storagemanager.messaging.scope-fields[1]=facility_id",
        // Never let the scheduled drain fire on its own — this test invokes drain() explicitly so
        // every outcome is deterministic.
        "storagemanager.messaging.outbox.poll-interval-ms=3600000",
        // Small batch so the batch-size cap (Req 6.6) is observable; small max-attempts and short
        // backoff so the retry -> park arc (Req 6.8/6.9) is reachable in a handful of drains.
        "storagemanager.messaging.outbox.batch-size=3",
        "storagemanager.messaging.max-attempts=3",
        "storagemanager.messaging.backoff.base=1s",
        "storagemanager.messaging.backoff.max=5m",
        "storagemanager.messaging.backoff.multiplier=2",
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventsIngestPublisherIntegrationTest {

    private static final String BASE_URL = "http://events.test";
    private static final String EVENTS_PATH = BASE_URL + "/api/v1/events";

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";

    /**
     * Supplies the host {@link OutboxTransport} the autoconfigured {@link OutboxPublisher} depends
     * on, built on a {@link RestClient} bound to a {@link MockRestServiceServer}. The builder and
     * the bound server are shared with the test via the static fields below so the test can set and
     * reset stub expectations per scenario.
     */
    @Configuration(proxyBeanMethods = false)
    static class StubbedTransportConfig {

        @Bean
        OutboxTransport eventsIngestTransport() {
            RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
            mockServer = MockRestServiceServer.bindTo(builder).build();
            return new EventsIngestTransport(builder.build());
        }
    }

    /** Bound to the transport's RestClient in {@link StubbedTransportConfig}; reset per test. */
    private static MockRestServiceServer mockServer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> outboxRepository.deleteAll());
        mockServer.reset();
    }

    @AfterEach
    void verifyServer() {
        mockServer.verify();
    }

    // ------------------------------------------------------------------------
    // Canonical envelope on the wire + 200 -> published (Req 7.1, 6.7, 8.3)
    // ------------------------------------------------------------------------

    @Test
    void drain_postsCanonicalEnvelope_andOn200StampsPublishedAndDoesNotReselect() {
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        append(fullyEnriched(eventId, organizationId, facilityId, correlationId, entityId));

        // Expect exactly one POST carrying the canonical 13-field envelope with scope lifted
        // positionally and the metadata headers lifted back (transport/wire-level assertions).
        mockServer.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    JsonNode body = objectMapper.readTree(request.getBody().toString());

                    assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                            "eventId", "eventType", "schemaVersion", "occurredAt",
                            "organizationId", "facilityId", "entityType", "entityId",
                            "actorType", "actorId", "correlationId", "causationId", "payload");

                    assertThat(body.get("eventId").asText()).isEqualTo(eventId.toString());
                    assertThat(body.get("eventType").asText()).isEqualTo("GenericEvent");
                    assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
                    assertThat(body.get("occurredAt").asText()).isNotBlank();

                    // Scope lifted positionally: scope_0 -> organizationId, scope_1 -> facilityId.
                    assertThat(body.get("organizationId").asText()).isEqualTo(organizationId.toString());
                    assertThat(body.get("facilityId").asText()).isEqualTo(facilityId.toString());

                    // Metadata headers lifted back onto the envelope.
                    assertThat(body.get("entityType").asText()).isEqualTo("Generic");
                    assertThat(body.get("entityId").asText()).isEqualTo(entityId.toString());
                    assertThat(body.get("actorType").asText()).isEqualTo("SYSTEM");
                    assertThat(body.get("correlationId").asText()).isEqualTo(correlationId.toString());

                    // actorId / causationId null when their headers are absent.
                    assertThat(body.get("actorId").isNull()).isTrue();
                    assertThat(body.get("causationId").isNull()).isTrue();

                    // Payload preserved.
                    JsonNode payload = body.get("payload");
                    assertThat(payload.get("entityId").asText()).isEqualTo(entityId.toString());
                    assertThat(payload.get("source").asText()).isEqualTo("TEST");
                })
                .andRespond(withSuccess());

        outboxPublisher.drain();

        // Publisher-level: the row is marked published (Req 6.7, 8.3).
        OutboxEntry row = outboxRepository.findByEventId(eventId).orElseThrow();
        assertThat(row.getPublishedAt()).as("published_at stamped on 200").isNotNull();
        assertThat(row.getAttempts()).as("no retry on success").isZero();

        // A second drain must NOT re-select the published row: no further request is expected
        // (verifyServer() would fail if the transport fired again).
        outboxPublisher.drain();
        assertThat(outboxRepository.findByEventId(eventId).orElseThrow().getPublishedAt())
                .as("still published after a second drain").isNotNull();
    }

    // ------------------------------------------------------------------------
    // 5xx -> attempts incremented, next_attempt_at advanced via backoff (Req 6.8)
    // ------------------------------------------------------------------------

    @Test
    void drain_on5xx_incrementsAttemptsAndAdvancesNextAttemptViaBackoff() {
        UUID eventId = UUID.randomUUID();
        append(minimalEnriched(eventId));

        Instant before = Instant.now();
        mockServer.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        outboxPublisher.drain();

        OutboxEntry row = outboxRepository.findByEventId(eventId).orElseThrow();
        assertThat(row.getPublishedAt()).as("still unpublished after a retryable failure").isNull();
        assertThat(row.getAttempts()).as("attempts incremented on 5xx").isEqualTo(1);
        // Backoff pushes next_attempt_at into the future (base=1s), so the row is not immediately
        // due again (Req 6.8).
        assertThat(row.getNextAttemptAt())
                .as("next_attempt_at advanced via backoff")
                .isNotNull()
                .isAfter(before);
    }

    @Test
    void drain_on5xx_doesNotReselectUntilNextAttemptIsDue() {
        UUID eventId = UUID.randomUUID();
        append(minimalEnriched(eventId));

        // First drain: 5xx -> attempts=1, next_attempt_at ~ now+1s (not yet due).
        mockServer.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        outboxPublisher.drain();

        // Immediate second drain: the row's backoff window has NOT elapsed, so it must not be
        // re-selected and no further request is expected.
        outboxPublisher.drain();

        OutboxEntry row = outboxRepository.findByEventId(eventId).orElseThrow();
        assertThat(row.getAttempts()).as("not retried while backoff window is open").isEqualTo(1);
    }

    // ------------------------------------------------------------------------
    // Driven to max-attempts -> parked (Req 6.9)
    // ------------------------------------------------------------------------

    @Test
    void drain_repeated5xx_reachesMaxAttemptsAndParksTheRow() {
        UUID eventId = UUID.randomUUID();
        append(minimalEnriched(eventId));

        // max-attempts = 3. Each drain returns 5xx; between drains we force the row due by clearing
        // next_attempt_at so the next drain re-selects it (simulating the backoff window elapsing).
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockServer.expect(requestTo(EVENTS_PATH))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
            outboxPublisher.drain();

            OutboxEntry row = outboxRepository.findByEventId(eventId).orElseThrow();
            assertThat(row.getAttempts()).isEqualTo(attempt);

            if (attempt < 3) {
                // Make the row due again for the next drain (its backoff window elapsed).
                makeDueNow(eventId);
                mockServer.reset();
            }
        }

        // After the 3rd 5xx, attempts == max-attempts (3): the row is parked and the
        // attempts < max-attempts filter stops selecting it (Req 6.9).
        OutboxEntry parked = outboxRepository.findByEventId(eventId).orElseThrow();
        assertThat(parked.getPublishedAt()).as("parked row stays unpublished").isNull();
        assertThat(parked.getAttempts()).as("attempts pinned at max-attempts").isEqualTo(3);
        assertThat(parked.getNextAttemptAt()).as("next_attempt_at cleared at park").isNull();

        // Even forcing the row 'due' (null next_attempt_at), a further drain must not re-select it
        // because attempts >= max-attempts. No further request is expected.
        outboxPublisher.drain();
        assertThat(outboxRepository.findByEventId(eventId).orElseThrow().getAttempts()).isEqualTo(3);
    }

    // ------------------------------------------------------------------------
    // 400 / 403 -> parked immediately (Req 7.3 permanent failure)
    // ------------------------------------------------------------------------

    @Test
    void drain_on400_parksRowImmediately() {
        UUID eventId = UUID.randomUUID();
        append(minimalEnriched(eventId));

        mockServer.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        outboxPublisher.drain();

        OutboxEntry row = outboxRepository.findByEventId(eventId).orElseThrow();
        assertThat(row.getPublishedAt()).as("permanent failure leaves row unpublished").isNull();
        assertThat(row.getAttempts()).as("400 parks immediately (attempts pinned at max)").isEqualTo(3);
        assertThat(row.getNextAttemptAt()).as("next_attempt_at cleared on park").isNull();

        // Parked: a further drain never re-selects it; no further request expected.
        outboxPublisher.drain();
        assertThat(outboxRepository.findByEventId(eventId).orElseThrow().getAttempts()).isEqualTo(3);
    }

    @Test
    void drain_on403_parksRowImmediately() {
        UUID eventId = UUID.randomUUID();
        append(minimalEnriched(eventId));

        mockServer.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        outboxPublisher.drain();

        OutboxEntry row = outboxRepository.findByEventId(eventId).orElseThrow();
        assertThat(row.getPublishedAt()).as("permanent failure leaves row unpublished").isNull();
        assertThat(row.getAttempts()).as("403 parks immediately (attempts pinned at max)").isEqualTo(3);
        assertThat(row.getNextAttemptAt()).as("next_attempt_at cleared on park").isNull();
    }

    // ------------------------------------------------------------------------
    // Only due rows, capped at batch-size, are delivered (Req 6.6)
    // ------------------------------------------------------------------------

    @Test
    void drain_selectsOnlyDueRowsUpToBatchSize() {
        // Seed 5 unpublished rows: 4 due (next_attempt_at null) + 1 undue (next_attempt_at far in
        // the future). batch-size = 3, so a single drain delivers exactly 3 of the 4 due rows and
        // never the undue one.
        List<UUID> dueIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            append(minimalEnriched(id));
            dueIds.add(id);
        }
        UUID undueId = UUID.randomUUID();
        append(minimalEnriched(undueId));
        // Push the undue row's next_attempt_at far into the future so it is not selected.
        setNextAttemptAt(undueId, Instant.now().plusSeconds(3600));

        // Exactly 3 deliveries expected (batch-size), each a 200.
        for (int i = 0; i < 3; i++) {
            mockServer.expect(requestTo(EVENTS_PATH))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess());
        }

        outboxPublisher.drain();

        long published = dueIds.stream()
                .map(id -> outboxRepository.findByEventId(id).orElseThrow())
                .filter(r -> r.getPublishedAt() != null)
                .count();
        assertThat(published).as("exactly batch-size due rows delivered in one drain").isEqualTo(3L);

        // The undue row is never selected: still unpublished, no attempts.
        OutboxEntry undue = outboxRepository.findByEventId(undueId).orElseThrow();
        assertThat(undue.getPublishedAt()).as("undue row not delivered").isNull();
        assertThat(undue.getAttempts()).as("undue row not attempted").isZero();
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** Append an enriched message inside a committing transaction (writer is MANDATORY). */
    private void append(EnrichedMessage message) {
        txTemplate.executeWithoutResult(status -> outboxWriter.append(message));
    }

    /** Clear next_attempt_at so the row is immediately due on the next drain. */
    private void makeDueNow(UUID eventId) {
        setNextAttemptAt(eventId, null);
    }

    private void setNextAttemptAt(UUID eventId, Instant when) {
        txTemplate.executeWithoutResult(status -> {
            OutboxEntry row = outboxRepository.findByEventId(eventId).orElseThrow();
            row.setNextAttemptAt(when);
            outboxRepository.save(row);
        });
    }

    /** A fully-enriched generic message carrying scope, correlation id, and metadata headers. */
    private static EnrichedMessage fullyEnriched(UUID eventId, UUID organizationId, UUID facilityId,
                                                 UUID correlationId, UUID entityId) {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put(ORG, organizationId);
        scope.put(FAC, facilityId);

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityId", entityId.toString());
        payload.put("source", "TEST");

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", Instant.now().toString());
        headers.put("entity_type", "Generic");
        headers.put("entity_id", entityId.toString());
        headers.put("actor_type", "SYSTEM");

        return new EnrichedMessage(eventId, "GenericEvent", payload, scope, correlationId, headers);
    }

    /** A minimal generic enriched message sufficient to drive publisher-level outcomes. */
    private static EnrichedMessage minimalEnriched(UUID eventId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityId", UUID.randomUUID().toString());
        payload.put("source", "TEST");

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", Instant.now().toString());
        headers.put("entity_type", "Generic");
        headers.put("entity_id", UUID.randomUUID().toString());
        headers.put("actor_type", "SYSTEM");

        return new EnrichedMessage(eventId, "GenericEvent", payload, new LinkedHashMap<>(), null, headers);
    }

    private static List<String> fieldNames(JsonNode node) {
        ArrayList<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
