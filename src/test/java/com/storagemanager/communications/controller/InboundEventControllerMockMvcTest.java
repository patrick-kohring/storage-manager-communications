package com.storagemanager.communications.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.OutboxTransport;
import com.storagemanager.messaging.api.OutboxWriter;
import com.storagemanager.messaging.internal.InboxRepository;
import com.storagemanager.messaging.internal.OutboxPublisher;

/**
 * Integration-ish MockMvc tests for the generic ingest route and its security posture (task 11.3).
 *
 * <p>This boots the full application via {@link SpringBootTest} + {@link AutoConfigureMockMvc} so
 * the real security filter chain, the {@link InboundEventController}, the {@link IngestAuthorizer},
 * the {@link com.storagemanager.communications.service.InboundRecordingService}, and the starter's
 * {@link IdempotentInbox} all participate. The real {@code inbox} table is created by Hibernate
 * from the starter's {@code InboxEntry} entity ({@code ddl-auto=create-drop}, Flyway disabled for
 * context slices — the real {@code V2} DDL is validated separately by the migration smoke test),
 * so the test can assert the inbox row directly via {@link InboxRepository}.
 *
 * <p>Security wiring is exercised hermetically: a {@link MockBean} {@link JwtDecoder} prevents the
 * resource-server decoder bean from attempting a JWKS fetch at context start, and
 * spring-security-test's {@code jwt()} request post-processor supplies an authenticated token with
 * the {@code SCOPE_events/ingest} authority (a {@code Trusted_Ingest_Caller}) without needing a
 * real issuer. The no-token case sends no post-processor so the anonymous request is rejected.
 *
 * <ul>
 *   <li>A valid envelope with a trusted {@code events/ingest} token returns {@code 200} and leaves
 *       exactly one inbox row keyed on the envelope {@code eventId} (Req 3.1, 3.2).</li>
 *   <li>No token -> {@code 401} with no inbox row (Req 4.2).</li>
 *   <li>Malformed JSON -> {@code 400} with no inbox row (Req 3.5).</li>
 *   <li>A missing required field -> {@code 400} with no inbox row (Req 3.4, exercised here as an
 *       additional guard; the dedicated property test is 11.8).</li>
 *   <li>The context starts with both halves enabled: {@link IdempotentInbox}, {@link OutboxWriter},
 *       {@link OutboxPublisher}, and the host {@link OutboxTransport} beans are present — the
 *       starter fail-fast "outbox enabled requires an OutboxTransport bean" is satisfied
 *       (Req 1.6, 1.7).</li>
 * </ul>
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.5, 4.1, 4.2, 1.6, 1.7
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // H2 in PostgreSQL mode WITH the JSONB domain alias so the starter's enriched
        // inbox/outbox JSONB payload/headers columns materialize under create-drop (Req 2.10).
        "spring.datasource.url=jdbc:h2:mem:comm_ingest_mvc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        // Both halves on + enrichment on, matching production application.properties. The test
        // resource application.properties does not declare these, so they are set here (otherwise
        // the recording service's scope names are rejected as Undeclared Scope_Fields).
        "storagemanager.messaging.inbox.enabled=true",
        "storagemanager.messaging.outbox.enabled=true",
        "storagemanager.messaging.enrichment.enabled=true",
        // Ordered scope-fields: index 0 -> scope_0 (organization_id), index 1 -> scope_1 (facility_id).
        "storagemanager.messaging.scope-fields[0]=organization_id",
        "storagemanager.messaging.scope-fields[1]=facility_id",
        // Push the scheduled outbox drain far out so the publisher never fires during the test —
        // this test asserts the ingest/inbox route and security contract, not drain behavior.
        "storagemanager.messaging.outbox.poll-interval-ms=3600000"
})
class InboundEventControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InboxRepository inboxRepository;

    // Beans asserted to be present so the "both halves enabled" contract is proven (Req 1.6, 1.7).
    @Autowired
    private IdempotentInbox idempotentInbox;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxTransport outboxTransport;

    /**
     * Mocked so the resource-server {@link JwtDecoder} bean does not attempt a JWKS network fetch
     * at context start; the {@code jwt()} post-processor bypasses it for authenticated requests.
     */
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final String INGEST_PATH = "/api/v1/communications";
    private static final String INGEST_AUTHORITY = "SCOPE_events/ingest";

    @BeforeEach
    void clearInbox() {
        inboxRepository.deleteAll();
    }

    /** A valid, fully-populated 13-field canonical envelope keyed on the given eventId. */
    private EventEnvelope validEnvelope(UUID eventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityId", UUID.randomUUID().toString());
        payload.put("source", "TEST");
        return new EventEnvelope(
                eventId,
                "GenericEvent",
                1,
                Instant.parse("2024-01-01T00:00:00Z"),
                UUID.randomUUID(),      // organizationId
                UUID.randomUUID(),      // facilityId
                "Generic",              // entityType
                UUID.randomUUID(),      // entityId
                EventEnvelope.ActorType.SYSTEM,
                null,                   // actorId
                null,                   // correlationId
                null,                   // causationId
                payload);
    }

    // Req 1.6, 1.7: both halves wire up and the host transport satisfies the publisher dependency.
    @Test
    void contextStartsWithBothHalvesAndHostTransport() {
        assertThat(idempotentInbox).as("inbox half active (Req 1.2)").isNotNull();
        assertThat(outboxWriter).as("outbox writer active (Req 1.3)").isNotNull();
        assertThat(outboxPublisher).as("outbox publisher active (Req 1.3)").isNotNull();
        assertThat(outboxTransport).as("host OutboxTransport present (Req 1.6, 1.7)").isNotNull();
    }

    // Req 3.1, 3.2, 4.1: a valid body with a Trusted_Ingest_Caller token returns 200 and leaves
    // exactly one inbox row for the envelope eventId.
    @Test
    void validEnvelopeWithTrustedTokenReturns200AndLeavesExactlyOneInboxRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = validEnvelope(eventId);

        mockMvc.perform(post(INGEST_PATH)
                        .with(jwt().authorities(new SimpleGrantedAuthority(INGEST_AUTHORITY)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isOk());

        assertThat(inboxRepository.count())
                .as("exactly one inbox row after a valid accepted ingest")
                .isEqualTo(1L);
        assertThat(inboxRepository.findByMessageId(eventId))
                .as("the inbox row is keyed on the envelope eventId (message_id = eventId)")
                .isPresent();
    }

    // Req 4.2: no token -> 401 and no inbox row written.
    @Test
    void noTokenReturns401AndWritesNoInboxRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = validEnvelope(eventId);

        mockMvc.perform(post(INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(envelope)))
                .andExpect(status().isUnauthorized());

        assertThat(inboxRepository.count())
                .as("no inbox row for an unauthenticated request")
                .isZero();
    }

    // Req 3.5: a malformed (unparseable) JSON body -> 400 and no inbox row written.
    @Test
    void malformedJsonReturns400AndWritesNoInboxRow() throws Exception {
        String malformed = "{ this is not valid json ";

        mockMvc.perform(post(INGEST_PATH)
                        .with(jwt().authorities(new SimpleGrantedAuthority(INGEST_AUTHORITY)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformed))
                .andExpect(status().isBadRequest());

        assertThat(inboxRepository.count())
                .as("no inbox row for a malformed body")
                .isZero();
    }

    // Req 3.4 (additional guard): a well-formed JSON body missing a required field (eventType)
    // -> 400 and no inbox row written. The dedicated property test is 11.8.
    @Test
    void missingRequiredFieldReturns400AndWritesNoInboxRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId.toString());
        // eventType omitted (required, @NotNull) -> validation failure -> 400.
        body.put("schemaVersion", 1);
        body.put("occurredAt", "2024-01-01T00:00:00Z");
        body.put("organizationId", UUID.randomUUID().toString());
        body.put("entityType", "Generic");
        body.put("entityId", UUID.randomUUID().toString());
        body.put("actorType", "SYSTEM");
        body.put("payload", Map.of("k", "v"));

        mockMvc.perform(post(INGEST_PATH)
                        .with(jwt().authorities(new SimpleGrantedAuthority(INGEST_AUTHORITY)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertThat(inboxRepository.count())
                .as("no inbox row when a required field is missing")
                .isZero();
    }
}
