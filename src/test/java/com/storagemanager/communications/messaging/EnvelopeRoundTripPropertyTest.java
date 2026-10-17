package com.storagemanager.communications.messaging;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.messaging.api.EnrichedMessage;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based test for the lossless, invertible events-boundary envelope round-trip.
 *
 * <p>Feature: messaging, Property 6: Lossless, invertible envelope round-trip — for arbitrary
 * valid 13-field inputs, {@code EventEnvelope} -> {@code EnrichedMessage}/{@code InboundMessage}
 * -> canonical {@code EventEnvelope} JSON reconstructs the same 13 fields (scope lifted to
 * {@code organizationId}/{@code facilityId}, the seven metadata headers lifted back,
 * {@code id} <-> {@code eventId}, byte-for-byte {@code payload}, null {@code facilityId}/
 * {@code actorId}/{@code causationId} preserved).
 *
 * <p>The forward mapping replicates {@code InboundRecordingService.toInboundMessage} (envelope ->
 * enriched members: scope {@code organization_id}/{@code facility_id}, {@code correlationId},
 * the seven metadata headers with {@code actor_id}/{@code causation_id} only when non-null,
 * {@code payload}). The reverse mapping replicates the documented
 * {@code EventsIngestTransport.toCanonicalEnvelope} logic (scope -> {@code organizationId}/
 * {@code facilityId}, headers -> {@code schemaVersion}/{@code occurredAt}/{@code entityType}/
 * {@code entityId}/{@code actorType}/{@code actorId}/{@code causationId}, {@code id} ->
 * {@code eventId}, {@code payload} passthrough).
 *
 * <p>Subtleties exercised: {@code schemaVersion} travels as a header string and must parse back
 * to the same {@code int}; {@code occurredAt} travels as an ISO-8601 string and must parse back
 * to the same {@code Instant} (generated at micro precision so {@code Instant.toString()}/
 * {@code parse} round-trips exactly); {@code payload} values are kept JSON-simple so equality
 * holds byte-for-byte via {@code Map.equals}.
 *
 * **Validates: Requirements 7.1, 8.4**
 */
class EnvelopeRoundTripPropertyTest {

    // ── Arbitraries ──

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    /**
     * Instants truncated to microsecond precision so the ISO-8601 header string produced by
     * {@link Instant#toString()} parses back to the identical {@link Instant}. Bounded to a
     * sane epoch-second range to keep the generated strings canonical.
     */
    @Provide
    Arbitrary<Instant> occurredAtInstants() {
        return Arbitraries.longs()
                .between(0L, 4_102_444_800L) // 1970-01-01 .. 2100-01-01
                .flatMap(seconds -> Arbitraries.longs().between(0L, 999_999L)
                        .map(micros -> Instant.ofEpochSecond(seconds)
                                .plus(micros, ChronoUnit.MICROS)
                                .truncatedTo(ChronoUnit.MICROS)));
    }

    @Provide
    Arbitrary<String> eventTypes() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(40);
    }

    @Provide
    Arbitrary<String> entityTypes() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
    }

    @Provide
    Arbitrary<Integer> schemaVersions() {
        return Arbitraries.integers().between(0, 1000);
    }

    @Provide
    Arbitrary<ActorType> actorTypes() {
        return Arbitraries.of(ActorType.SYSTEM, ActorType.USER);
    }

    /** A nullable UUID: roughly a third of the time this is null to exercise the absent path. */
    @Provide
    Arbitrary<UUID> nullableUuids() {
        return Arbitraries.oneOf(
                Arbitraries.just((UUID) null),
                Arbitraries.create(UUID::randomUUID));
    }

    /** JSON-simple payload values (strings / integers / booleans) so Map.equals holds exactly. */
    @Provide
    Arbitrary<Map<String, Object>> payloads() {
        Arbitrary<String> keys = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12);
        Arbitrary<Object> values = Arbitraries.oneOf(
                Arbitraries.strings().alpha().numeric().ofMaxLength(20).map(s -> (Object) s),
                Arbitraries.integers().between(-10_000, 10_000).map(i -> (Object) i),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(b -> (Object) b));
        return Arbitraries.maps(keys, values).ofMinSize(0).ofMaxSize(6)
                .map(m -> {
                    // Preserve insertion order to mirror the LinkedHashMap carried on the wire.
                    LinkedHashMap<String, Object> ordered = new LinkedHashMap<>(m);
                    return ordered;
                });
    }

    @Provide
    Arbitrary<EventEnvelope> envelopes() {
        return Combinators.combine(
                        uuids(),            // eventId
                        eventTypes(),       // eventType
                        schemaVersions(),   // schemaVersion
                        occurredAtInstants(), // occurredAt
                        uuids(),            // organizationId
                        nullableUuids(),    // facilityId
                        entityTypes(),      // entityType
                        uuids())            // entityId
                .as(EnvelopeHead::new)
                .flatMap(head -> Combinators.combine(
                                actorTypes(),     // actorType
                                nullableUuids(),  // actorId
                                nullableUuids(),  // correlationId
                                nullableUuids(),  // causationId
                                payloads())       // payload
                        .as((actorType, actorId, correlationId, causationId, payload) ->
                                new EventEnvelope(
                                        head.eventId, head.eventType, head.schemaVersion,
                                        head.occurredAt, head.organizationId, head.facilityId,
                                        head.entityType, head.entityId, actorType, actorId,
                                        correlationId, causationId, payload)));
    }

    /** Carrier for the first eight generated fields (jqwik Combinators cap at 8 arguments). */
    private record EnvelopeHead(
            UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
            UUID organizationId, UUID facilityId, String entityType, UUID entityId) {
    }

    // ═══════════════════════════════════════════════════════════════
    // Property 6: EventEnvelope -> EnrichedMessage -> canonical EventEnvelope JSON is lossless
    // ═══════════════════════════════════════════════════════════════

    @Property(tries = 200)
    void envelopeRoundTripIsLossless(@ForAll("envelopes") EventEnvelope original) {
        // Forward: EventEnvelope -> EnrichedMessage (mirrors InboundRecordingService).
        EnrichedMessage enriched = toEnrichedMessage(original);

        // Reverse: EnrichedMessage -> canonical EventEnvelope JSON map
        // (mirrors EventsIngestTransport.toCanonicalEnvelope).
        Map<String, Object> canonical = toCanonicalEnvelope(enriched);

        // id <-> eventId
        assertThat(canonical.get("eventId")).isEqualTo(original.eventId());
        assertThat(canonical.get("eventType")).isEqualTo(original.eventType());

        // schemaVersion travels as a header string and parses back to the same int
        assertThat(canonical.get("schemaVersion")).isEqualTo(original.schemaVersion());

        // occurredAt travels as ISO-8601 and parses back to the same Instant
        assertThat(Instant.parse((String) canonical.get("occurredAt")))
                .isEqualTo(original.occurredAt());

        // scope lifted back to organizationId / facilityId (facilityId null preserved)
        assertThat(canonical.get("organizationId")).isEqualTo(original.organizationId());
        assertThat(canonical.get("facilityId")).isEqualTo(original.facilityId());

        assertThat(canonical.get("entityType")).isEqualTo(original.entityType());
        assertThat(canonical.get("entityId")).isEqualTo(original.entityId());
        assertThat(canonical.get("actorType")).isEqualTo(original.actorType().name());

        // nullable actorId / correlationId / causationId preserved exactly (null stays null)
        assertThat(canonical.get("actorId")).isEqualTo(original.actorId());
        assertThat(canonical.get("correlationId")).isEqualTo(original.correlationId());
        assertThat(canonical.get("causationId")).isEqualTo(original.causationId());

        // payload passed through byte-for-byte (deep Map equality)
        assertThat(canonical.get("payload")).isEqualTo(original.payload());

        // Reconstruct a full EventEnvelope and assert field-wise equality to the original.
        EventEnvelope reconstructed = reconstructEnvelope(canonical);
        assertThat(reconstructed).isEqualTo(original);
    }

    // ── Forward mapping: EventEnvelope -> EnrichedMessage (mirrors InboundRecordingService) ──

    private static EnrichedMessage toEnrichedMessage(EventEnvelope e) {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put("organization_id", e.organizationId());
        scope.put("facility_id", e.facilityId()); // null -> scope_1 null, no sentinel

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

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>(e.payload());

        return new EnrichedMessage(
                e.eventId(),       // id == eventId (Idempotency_Key)
                e.eventType(),
                payload,
                scope,
                e.correlationId(),
                headers);
    }

    // ── Reverse mapping: EnrichedMessage -> canonical envelope map
    //    (mirrors EventsIngestTransport.toCanonicalEnvelope) ──

    private static Map<String, Object> toCanonicalEnvelope(EnrichedMessage m) {
        Map<String, UUID> scope = m.scope();
        Map<String, String> h = m.headers();

        LinkedHashMap<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", m.id());
        e.put("eventType", m.eventType());
        e.put("schemaVersion", intHeader(h, "schema_version"));
        e.put("occurredAt", h.get("occurred_at"));
        e.put("organizationId", scope.get("organization_id"));
        e.put("facilityId", scope.get("facility_id"));
        e.put("entityType", h.get("entity_type"));
        e.put("entityId", uuidHeader(h, "entity_id"));
        e.put("actorType", h.get("actor_type"));
        e.put("actorId", uuidHeader(h, "actor_id"));
        e.put("correlationId", m.correlationId());
        e.put("causationId", uuidHeader(h, "causation_id"));
        e.put("payload", m.payload());
        return e;
    }

    private static EventEnvelope reconstructEnvelope(Map<String, Object> c) {
        return new EventEnvelope(
                (UUID) c.get("eventId"),
                (String) c.get("eventType"),
                (Integer) c.get("schemaVersion"),
                Instant.parse((String) c.get("occurredAt")),
                (UUID) c.get("organizationId"),
                (UUID) c.get("facilityId"),
                (String) c.get("entityType"),
                (UUID) c.get("entityId"),
                ActorType.valueOf((String) c.get("actorType")),
                (UUID) c.get("actorId"),
                (UUID) c.get("correlationId"),
                (UUID) c.get("causationId"),
                asPayloadMap(c.get("payload")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asPayloadMap(Object payload) {
        return (Map<String, Object>) payload;
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
