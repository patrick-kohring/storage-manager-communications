package com.storagemanager.communications.messaging;

import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagemanager.messaging.api.EnrichedMessage;
import com.storagemanager.messaging.api.TransportResult;

/**
 * Unit tests for {@link EventsIngestTransport}: the wire-side events-boundary mapping
 * ({@link EnrichedMessage} &rarr; canonical {@code EventEnvelope} JSON) and the HTTP
 * outcome &rarr; {@link TransportResult} mapping.
 *
 * <p>Drives real HTTP outcomes through a {@link RestClient} bound to a
 * {@link MockRestServiceServer}, which also lets us capture and assert the POSTed body.
 *
 * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.4
 */
class EventsIngestTransportTest {

    private static final String BASE_URL = "http://events.test";
    private static final String EVENTS_PATH = BASE_URL + "/api/v1/events";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockRestServiceServer server;
    private EventsIngestTransport transport;

    @BeforeEach
    void setUp() {
        RestClient.Builder clientBuilder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(clientBuilder).build();
        transport = new EventsIngestTransport(clientBuilder.build());
    }

    // ------------------------------------------------------------------------
    // TransportResult mapping (Req 7.2–7.4)
    // ------------------------------------------------------------------------

    @Test
    void send_on200_returnsSuccess() {
        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        TransportResult result = transport.send(sampleMessage());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result).isInstanceOf(TransportResult.Success.class);
        server.verify();
    }

    @Test
    void send_on400_returnsPermanentFailure() {
        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        TransportResult result = transport.send(sampleMessage());

        assertThat(result).isInstanceOf(TransportResult.PermanentFailure.class);
        assertThat(result.isSuccess()).isFalse();
        server.verify();
    }

    @Test
    void send_on403_returnsPermanentFailure() {
        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        TransportResult result = transport.send(sampleMessage());

        assertThat(result).isInstanceOf(TransportResult.PermanentFailure.class);
        assertThat(result.isSuccess()).isFalse();
        server.verify();
    }

    @Test
    void send_on500_returnsRetryableFailure() {
        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        TransportResult result = transport.send(sampleMessage());

        assertThat(result).isInstanceOf(TransportResult.RetryableFailure.class);
        assertThat(result.isSuccess()).isFalse();
        server.verify();
    }

    @Test
    void send_on503_returnsRetryableFailure() {
        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        TransportResult result = transport.send(sampleMessage());

        assertThat(result).isInstanceOf(TransportResult.RetryableFailure.class);
        server.verify();
    }

    @Test
    void send_onTransportException_returnsRetryableFailure() {
        // A low-level I/O error (e.g. connection reset / connection refused) surfaces as a
        // RestClientException rather than an HTTP status; the transport maps it to Retryable
        // so the publisher backs off and retries rather than dropping the row (Req 7.4).
        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new java.io.IOException("connection reset");
                });

        TransportResult result = transport.send(sampleMessage());

        assertThat(result).isInstanceOf(TransportResult.RetryableFailure.class);
        assertThat(result.isSuccess()).isFalse();
        server.verify();
    }

    // ------------------------------------------------------------------------
    // Canonical envelope reconstruction (Req 7.1)
    // ------------------------------------------------------------------------

    @Test
    void send_postsCanonicalEventEnvelope() {
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        EnrichedMessage message = messageWith(
                eventId, organizationId, facilityId, entityId, correlationId);

        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    JsonNode body = objectMapper.readTree(request.getBody().toString());

                    // id -> eventId
                    assertThat(body.get("eventId").asText()).isEqualTo(eventId.toString());
                    // eventType passthrough (generic in this base scope)
                    assertThat(body.get("eventType").asText()).isEqualTo("GenericEvent");
                    // scope lifted to organizationId / facilityId
                    assertThat(body.get("organizationId").asText())
                            .isEqualTo(organizationId.toString());
                    assertThat(body.get("facilityId").asText()).isEqualTo(facilityId.toString());
                    // schema_version header lifted as an int
                    assertThat(body.get("schemaVersion").isInt()).isTrue();
                    assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
                    // entity_type / entity_id / actor_type headers lifted
                    assertThat(body.get("entityType").asText()).isEqualTo("Thing");
                    assertThat(body.get("entityId").asText()).isEqualTo(entityId.toString());
                    assertThat(body.get("actorType").asText()).isEqualTo("SYSTEM");
                    // correlationId present
                    assertThat(body.get("correlationId").asText())
                            .isEqualTo(correlationId.toString());
                    // actorId / causationId null when their headers are absent
                    assertThat(body.hasNonNull("actorId")).isFalse();
                    assertThat(body.hasNonNull("causationId")).isFalse();
                    // occurredAt carried through
                    assertThat(body.get("occurredAt").asText()).isNotBlank();
                    // payload passthrough (byte-for-byte)
                    JsonNode payload = body.get("payload");
                    assertThat(payload.get("foo").asText()).isEqualTo("bar");
                    assertThat(payload.get("count").asInt()).isEqualTo(42);
                })
                .andRespond(withSuccess());

        TransportResult result = transport.send(message);

        assertThat(result.isSuccess()).isTrue();
        server.verify();
    }

    @Test
    void send_nullsActorIdAndCausationId_whenHeadersAbsent() {
        EnrichedMessage message = sampleMessage(); // actor_id / causation_id intentionally absent

        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = objectMapper.readTree(request.getBody().toString());
                    assertThat(body.hasNonNull("actorId")).isFalse();
                    assertThat(body.hasNonNull("causationId")).isFalse();
                })
                .andRespond(withSuccess());

        TransportResult result = transport.send(message);

        assertThat(result.isSuccess()).isTrue();
        server.verify();
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** A representative generic enriched message with actor_id/causation_id absent. */
    private static EnrichedMessage sampleMessage() {
        return messageWith(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
    }

    private static EnrichedMessage messageWith(
            UUID eventId, UUID organizationId, UUID facilityId, UUID entityId, UUID correlationId) {

        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put("organization_id", organizationId);
        scope.put("facility_id", facilityId);

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("foo", "bar");
        payload.put("count", 42);

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", java.time.Instant.now().toString());
        headers.put("entity_type", "Thing");
        headers.put("entity_id", entityId.toString());
        headers.put("actor_type", "SYSTEM");
        // actor_id and causation_id intentionally absent

        return new EnrichedMessage(
                eventId, "GenericEvent", payload, scope, correlationId, headers);
    }
}
