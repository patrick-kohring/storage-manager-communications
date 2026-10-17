package com.storagemanager.communications.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.storagemanager.messaging.api.EnrichedMessage;
import com.storagemanager.messaging.api.OutboxMessage;
import com.storagemanager.messaging.api.OutboxTransport;
import com.storagemanager.messaging.api.TransportResult;

/**
 * Host OutboxTransport that reconstructs the events-canonical 13-field EventEnvelope JSON from an
 * EnrichedMessage and POSTs it to events /api/v1/events (Req 7.1). Maps 2xx -> Success,
 * 400/403 -> PermanentFailure, 5xx/network/timeout -> RetryableFailure (Req 7.2-7.4).
 */
public class EventsIngestTransport implements OutboxTransport {

    private final RestClient restClient; // configured with events base URL + optional s2s auth

    public EventsIngestTransport(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public TransportResult send(EnrichedMessage message) {
        Map<String, Object> envelope = toCanonicalEnvelope(message);
        try {
            restClient.post()
                    .uri("/api/v1/events")
                    .body(envelope)
                    .retrieve()
                    .toBodilessEntity();                 // events returns 200 for ACCEPTED and DUPLICATE
            return TransportResult.success();             // redelivery is safe (dedupe on eventId)
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400) {
                return TransportResult.permanent("events rejected envelope (400): " + e.getResponseBodyAsString());
            }
            if (status == 403) {
                return TransportResult.permanent("events cross-org rejection (403)");
            }
            if (status >= 500) {
                return TransportResult.retryable("events server error (" + status + ")");
            }
            return TransportResult.permanent("events rejected (" + status + ")"); // other 4xx -> permanent
        } catch (RestClientException e) {
            // Connection refused, timeout, DNS, etc. -> transient, back off and retry.
            return TransportResult.retryable("transport error: " + e.getMessage());
        }
    }

    /** Baseline path kept source-complete; not the primary path (publisher calls the enriched overload). */
    @Override
    public TransportResult send(OutboxMessage message) {
        return send(EnrichedMessage.of(message));
    }

    /** Rebuild the 13-field canonical EventEnvelope JSON from the enriched members. */
    private Map<String, Object> toCanonicalEnvelope(EnrichedMessage m) {
        Map<String, UUID> scope = m.scope();
        Map<String, String> h = m.headers();

        LinkedHashMap<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", m.id());
        e.put("eventType", m.eventType());
        e.put("schemaVersion", intHeader(h, "schema_version"));
        e.put("occurredAt", h.get("occurred_at"));
        e.put("organizationId", scope.get("organization_id"));   // scope_0 -> organizationId
        e.put("facilityId", scope.get("facility_id"));           // scope_1 -> facilityId (nullable)
        e.put("entityType", h.get("entity_type"));
        e.put("entityId", uuidHeader(h, "entity_id"));
        e.put("actorType", h.get("actor_type"));
        e.put("actorId", uuidHeader(h, "actor_id"));             // null when absent
        e.put("correlationId", m.correlationId());
        e.put("causationId", uuidHeader(h, "causation_id"));     // null when absent
        e.put("payload", m.payload());                           // opaque passthrough
        return e;
    }

    private static Integer intHeader(Map<String, String> h, String k) {
        String v = h.get(k);
        return v == null ? null : Integer.valueOf(v);
    }

    private static UUID uuidHeader(Map<String, String> h, String k) {
        String v = h.get(k);
        return v == null ? null : UUID.fromString(v);
    }
}
