package com.storagemanager.communications.inbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.messaging.api.InboundMessage;

/**
 * Reconstructs an EventEnvelope from an InboundMessage read-view (Req 3). The mapping is the exact
 * inverse of InboundRecordingService.toInboundMessage: scope organization_id/facility_id ->
 * organizationId/facilityId (Req 3.4, 8.1, 8.2); messageId -> eventId (Req 3.2, 8.3); correlationId
 * carried through (Req 3.3, 7); the seven metadata header keys rebuild the remaining fields
 * (Req 3.5). A required header that is absent or unparseable is a terminal reconstruction failure
 * (Req 3.6) raised as ReconstructionException naming the offending field (Req 3.7).
 */
@Component
public class EnvelopeReconstructor {

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";

    public EventEnvelope reconstruct(InboundMessage m) {
        LinkedHashMap<String, UUID> scope =
                m.scope() != null ? m.scope() : new LinkedHashMap<>();
        Map<String, String> h = m.headers() != null ? m.headers() : Map.of();

        UUID organizationId = requireScope(scope, ORG);             // Req 3.4, 8.1
        UUID facilityId = scope.get(FAC);                            // nullable, no sentinel (Req 8.2)

        int schemaVersion = parseInt(h, "schema_version");          // Req 3.5
        Instant occurredAt = parseInstant(h, "occurred_at");
        String entityType = requireHeader(h, "entity_type");
        UUID entityId = parseUuid(h, "entity_id");
        ActorType actorType = parseActorType(h, "actor_type");
        UUID actorId = optionalUuid(h, "actor_id");                 // nullable by contract
        UUID causationId = optionalUuid(h, "causation_id");         // nullable by contract

        return new EventEnvelope(
                m.messageId(),          // eventId = messageId, preserved key (Req 3.2, 8.3)
                m.eventType(),          // Req 3.1
                schemaVersion,
                occurredAt,
                organizationId,
                facilityId,
                entityType,
                entityId,
                actorType,
                actorId,
                m.correlationId(),      // Req 3.3, 7.1 / 7.2
                causationId,
                m.payload());           // Req 3.1
    }

    // --- helpers: a missing/unparseable REQUIRED field throws ReconstructionException(field) ---

    private UUID requireScope(Map<String, UUID> scope, String field) {
        UUID v = scope.get(field);
        if (v == null) {
            throw new ReconstructionException(field, "required scope value absent");
        }
        return v;
    }

    private String requireHeader(Map<String, String> h, String field) {
        String v = h.get(field);
        if (v == null || v.isBlank()) {
            throw new ReconstructionException(field, "required header absent");
        }
        return v;
    }

    private int parseInt(Map<String, String> h, String field) {
        try {
            return Integer.parseInt(requireHeader(h, field));
        } catch (NumberFormatException ex) {
            throw new ReconstructionException(field, "not an integer", ex);
        }
    }

    private Instant parseInstant(Map<String, String> h, String field) {
        try {
            return Instant.parse(requireHeader(h, field));
        } catch (RuntimeException ex) {
            throw new ReconstructionException(field, "not an ISO-8601 instant", ex);
        }
    }

    private UUID parseUuid(Map<String, String> h, String field) {
        try {
            return UUID.fromString(requireHeader(h, field));
        } catch (IllegalArgumentException ex) {
            throw new ReconstructionException(field, "not a UUID", ex);
        }
    }

    private ActorType parseActorType(Map<String, String> h, String field) {
        try {
            return ActorType.valueOf(requireHeader(h, field));
        } catch (IllegalArgumentException ex) {
            throw new ReconstructionException(field, "not a valid ActorType", ex);
        }
    }

    private UUID optionalUuid(Map<String, String> h, String field) {
        String v = h.get(field);
        if (v == null || v.isBlank()) {
            return null;                                            // nullable by contract
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException ex) {
            throw new ReconstructionException(field, "present but not a UUID", ex);
        }
    }
}
