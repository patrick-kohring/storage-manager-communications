package com.storagemanager.communications.inbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 4 — Scope and the idempotency key are preserved without substitution</b>.
 *
 * <p><b>Feature: inbox-action-dispatch, Property 4: Scope and the idempotency key are preserved
 * without substitution</b> — for any {@link InboundMessage}, the {@link EnvelopeReconstructor}
 * produces an {@link EventEnvelope} whose {@code organizationId} equals the scope value bound to
 * {@code organization_id}, whose {@code facilityId} equals the scope value bound to
 * {@code facility_id} (null when absent, with no sentinel or default substituted), and whose
 * {@code eventId} equals the message {@code messageId} with no new identifier generated.
 *
 * <p><b>Validates: Requirements 8.1, 8.2, 8.3</b>
 *
 * <h2>Approach</h2>
 * This is a <em>pure</em> property (no Spring context, no database): it drives
 * {@link EnvelopeReconstructor#reconstruct(InboundMessage)} directly with jqwik-generated
 * {@link InboundMessage}s. Each generated message carries the full set of valid required headers
 * ({@code schema_version}, {@code occurred_at}, {@code entity_type}, {@code entity_id},
 * {@code actor_type}) exactly as {@code InboundRecordingService.toInboundMessage} writes them, so
 * reconstruction always succeeds and the assertion focuses solely on scope/key preservation. The
 * {@code facility_id} scope entry is present for roughly half of the generated messages and absent
 * (so the reconstructed {@code facilityId} must be null) for the other half, exercising both arms of
 * Req 8.2.
 */
class ScopeAndKeyPreservationPropertyTest {

    private static final String ORG = "organization_id";
    private static final String FAC = "facility_id";

    private final EnvelopeReconstructor reconstructor = new EnvelopeReconstructor();

    /**
     * For any {@link InboundMessage}, reconstruction preserves the organization/facility scope and
     * the idempotency key without substitution: {@code organizationId == scope[organization_id]},
     * {@code facilityId == scope[facility_id]} (null preserved), and {@code eventId == messageId}.
     *
     * <p><b>Validates: Requirements 8.1, 8.2, 8.3</b>
     */
    @Property(tries = 100)
    void scopeAndIdempotencyKeyArePreservedWithoutSubstitution(
            @ForAll("inboundMessages") InboundMessage message) {

        EventEnvelope reconstructed = reconstructor.reconstruct(message);

        UUID expectedOrg = message.scope().get(ORG);
        UUID expectedFac = message.scope().get(FAC); // null when the facility scope is absent

        // Req 8.1: organization scope carried through without substitution.
        assertThat(reconstructed.organizationId())
                .as("organizationId is the scope organization_id value, unchanged")
                .isEqualTo(expectedOrg);

        // Req 8.2: facility scope carried through; null preserved, no sentinel/default substituted.
        assertThat(reconstructed.facilityId())
                .as("facilityId is the scope facility_id value (null when absent, no sentinel)")
                .isEqualTo(expectedFac);

        // Req 8.3: the messageId is the single preserved idempotency key on eventId; no new id.
        assertThat(reconstructed.eventId())
                .as("eventId is the preserved messageId, no new identifier generated")
                .isSameAs(message.messageId());
    }

    // =====================================================================
    // Generators
    // =====================================================================

    /**
     * {@link InboundMessage}s carrying a required {@code organization_id} scope value, a
     * {@code facility_id} scope value present for ~half the cases and absent for the rest, and the
     * full set of valid required reconstruction headers so {@code reconstruct(...)} always succeeds.
     */
    @Provide
    Arbitrary<InboundMessage> inboundMessages() {
        Arbitrary<UUID> messageIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> organizationIds = Arbitraries.create(UUID::randomUUID);
        // facilityId present (a fresh UUID) OR absent (null) to exercise both arms of Req 8.2.
        Arbitrary<UUID> facilityIds = Arbitraries.oneOf(
                Arbitraries.just((UUID) null),
                Arbitraries.create(UUID::randomUUID));
        Arbitrary<String> eventTypes =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(40);
        Arbitrary<UUID> correlationIds = Arbitraries.oneOf(
                Arbitraries.just((UUID) null),
                Arbitraries.create(UUID::randomUUID));

        return Combinators.combine(messageIds, organizationIds, facilityIds, eventTypes,
                correlationIds).as(ScopeAndKeyPreservationPropertyTest::buildMessage);
    }

    /**
     * Builds an {@link InboundMessage} mirroring {@code InboundRecordingService.toInboundMessage}:
     * the organization_id scope is always present; facility_id is written only when non-null (so an
     * absent facility is a missing scope key, not a null sentinel); the five required metadata
     * headers are written with valid, parseable values so reconstruction succeeds.
     */
    private static InboundMessage buildMessage(UUID messageId, UUID organizationId, UUID facilityId,
            String eventType, UUID correlationId) {

        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put(ORG, organizationId);
        if (facilityId != null) {
            scope.put(FAC, facilityId); // absent key when null — no sentinel (mirrors the service)
        }

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        headers.put("entity_type", "lead");
        headers.put("entity_id", UUID.randomUUID().toString());
        headers.put("actor_type", ActorType.SYSTEM.name());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "SendCommunication");

        return new InboundMessage(messageId, eventType, payload, scope, correlationId, headers);
    }
}
