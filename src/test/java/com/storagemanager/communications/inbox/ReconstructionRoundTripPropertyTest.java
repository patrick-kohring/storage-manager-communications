package com.storagemanager.communications.inbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.messaging.api.InboundMessage;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 2 — Reconstruction round-trip preserves the envelope</b>.
 *
 * <p><b>Feature: inbox-action-dispatch, Property 2: Reconstruction round-trip preserves the
 * envelope</b> — for any valid {@link EventEnvelope}, mapping it to an {@link InboundMessage}
 * exactly as {@code InboundRecordingService.toInboundMessage} does and then reconstructing it with
 * {@link EnvelopeReconstructor} produces an {@link EventEnvelope} equal to the original
 * field-by-field across all 13 fields ({@code eventId}, {@code eventType}, {@code schemaVersion},
 * {@code occurredAt}, {@code organizationId}, {@code facilityId}, {@code entityType},
 * {@code entityId}, {@code actorType}, {@code actorId}, {@code correlationId}, {@code causationId},
 * {@code payload}).
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5</b>
 *
 * <h2>Approach</h2>
 * This is a <em>pure</em> property (no database, no Spring context). It replicates the
 * {@code InboundRecordingService.toInboundMessage} mapping locally — the exact same mapping the
 * reconstructor inverts — so the round-trip is {@code reconstruct(toInboundMessage(env)) == env}.
 * Generators cover facility present/absent and {@code actorId}/{@code causationId} present/absent so
 * the nullable branches of both the forward mapping and the reconstruction are exercised.
 */
class ReconstructionRoundTripPropertyTest {

    private final EnvelopeReconstructor reconstructor = new EnvelopeReconstructor();

    /**
     * Feature: inbox-action-dispatch, Property 2: Reconstruction round-trip preserves the envelope.
     *
     * <p>For any valid {@link EventEnvelope}, {@code reconstruct(toInboundMessage(env))} equals
     * {@code env} field-by-field across all 13 fields.
     *
     * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void reconstructRoundTripPreservesEnvelope(@ForAll("envelopes") EventEnvelope original) {

        // Forward: map the envelope onto an InboundMessage exactly as InboundRecordingService does.
        InboundMessage message = toInboundMessage(original, original.organizationId());

        // Inverse: reconstruct the envelope from the read-view.
        EventEnvelope reconstructed = reconstructor.reconstruct(message);

        // Field-by-field equality across all 13 fields.
        assertThat(reconstructed.eventId()).as("eventId").isEqualTo(original.eventId());
        assertThat(reconstructed.eventType()).as("eventType").isEqualTo(original.eventType());
        assertThat(reconstructed.schemaVersion()).as("schemaVersion")
                .isEqualTo(original.schemaVersion());
        assertThat(reconstructed.occurredAt()).as("occurredAt").isEqualTo(original.occurredAt());
        assertThat(reconstructed.organizationId()).as("organizationId")
                .isEqualTo(original.organizationId());
        assertThat(reconstructed.facilityId()).as("facilityId").isEqualTo(original.facilityId());
        assertThat(reconstructed.entityType()).as("entityType").isEqualTo(original.entityType());
        assertThat(reconstructed.entityId()).as("entityId").isEqualTo(original.entityId());
        assertThat(reconstructed.actorType()).as("actorType").isEqualTo(original.actorType());
        assertThat(reconstructed.actorId()).as("actorId").isEqualTo(original.actorId());
        assertThat(reconstructed.correlationId()).as("correlationId")
                .isEqualTo(original.correlationId());
        assertThat(reconstructed.causationId()).as("causationId")
                .isEqualTo(original.causationId());
        assertThat(reconstructed.payload()).as("payload").isEqualTo(original.payload());

        // And the whole record is equal (records compare field-by-field).
        assertThat(reconstructed).as("full 13-field round-trip equality").isEqualTo(original);
    }

    // =====================================================================
    // Forward mapping — a faithful replica of InboundRecordingService.toInboundMessage
    // =====================================================================

    /**
     * Mirrors {@code InboundRecordingService.toInboundMessage}: scope_0 = organization_id,
     * scope_1 = facility_id (null preserved, no sentinel); the seven non-promoted fields ride as
     * headers, with {@code actor_id} / {@code causation_id} written only when present.
     */
    private static InboundMessage toInboundMessage(EventEnvelope e, UUID organizationScope) {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put("organization_id", organizationScope);
        scope.put("facility_id", e.facilityId());

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

    // =====================================================================
    // Generators
    // =====================================================================

    /**
     * A valid 13-field {@link EventEnvelope}: {@code facilityId}, {@code actorId}, and
     * {@code causationId} are each present roughly half the time and null the rest so the nullable
     * branches of the forward mapping and the reconstruction are both exercised.
     */
    @Provide
    Arbitrary<EventEnvelope> envelopes() {
        Arbitrary<UUID> eventId = uuids();
        Arbitrary<String> eventType = Arbitraries.of(
                "SendCommunication", "GenericEvent", "LeadCreated", "TenantUpdated");
        Arbitrary<Integer> schemaVersion = Arbitraries.integers().between(0, 1000);
        Arbitrary<Instant> occurredAt = instants();
        Arbitrary<UUID> organizationId = uuids();
        Arbitrary<UUID> facilityId = uuids().injectNull(0.5);
        Arbitrary<String> entityType = Arbitraries.of("Lead", "Tenant", "Lease", "Unit", "Entity");
        Arbitrary<UUID> entityId = uuids();
        Arbitrary<ActorType> actorType = Arbitraries.of(ActorType.SYSTEM, ActorType.USER);
        Arbitrary<UUID> actorId = uuids().injectNull(0.5);
        Arbitrary<UUID> correlationId = uuids().injectNull(0.5);
        Arbitrary<UUID> causationId = uuids().injectNull(0.5);
        Arbitrary<Map<String, Object>> payload = payloads();

        Arbitrary<Head> head = Combinators.combine(eventId, eventType, schemaVersion, occurredAt)
                .as((id, type, version, at) -> new Head(id, type, version, at));
        Arbitrary<Scope> scope = Combinators.combine(organizationId, facilityId, entityType, entityId)
                .as((org, fac, type, id) -> new Scope(org, fac, type, id));
        Arbitrary<Actor> actor = Combinators.combine(actorType, actorId, correlationId, causationId)
                .as((type, aId, corr, caus) -> new Actor(type, aId, corr, caus));

        return Combinators.combine(head, scope, actor, payload)
                .as((h, s, ac, pl) -> new EventEnvelope(
                        h.eventId(),
                        h.eventType(),
                        h.schemaVersion(),
                        h.occurredAt(),
                        s.organizationId(),
                        s.facilityId(),
                        s.entityType(),
                        s.entityId(),
                        ac.actorType(),
                        ac.actorId(),
                        ac.correlationId(),
                        ac.causationId(),
                        pl));
    }

    /** Intermediate holders so the 13-field envelope fits jqwik's 4-arg {@code Combinators}. */
    private record Head(UUID eventId, String eventType, int schemaVersion, Instant occurredAt) {}

    private record Scope(UUID organizationId, UUID facilityId, String entityType, UUID entityId) {}

    private record Actor(ActorType actorType, UUID actorId, UUID correlationId, UUID causationId) {}

    /**
     * Payloads of simple JSON-compatible entries. The reconstructor passes the payload through
     * opaquely, so the map is compared by value; keep values to scalar types that survive equality.
     */
    @Provide
    Arbitrary<Map<String, Object>> payloads() {
        Arbitrary<String> keys = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        Arbitrary<Object> values = Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMaxLength(12).map(s -> (Object) s),
                Arbitraries.integers().map(i -> (Object) i),
                Arbitraries.of(true, false).map(b -> (Object) b));
        return Arbitraries.maps(keys, values).ofMaxSize(5);
    }

    /** ISO-8601 instants that round-trip through {@link Instant#toString()} / {@link Instant#parse}. */
    private Arbitrary<Instant> instants() {
        return Arbitraries.longs()
                .between(0L, 4_102_444_800L) // 1970-01-01 .. 2100-01-01, whole seconds
                .map(Instant::ofEpochSecond);
    }

    private Arbitrary<UUID> uuids() {
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(UUID::new);
    }
}
