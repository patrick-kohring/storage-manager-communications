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
 * jqwik property test for correlation-id derivation on EventEnvelope reconstruction
 * (inbox-action-dispatch spec, Task 2.3).
 *
 * <p>Feature: inbox-action-dispatch, Property 3: Correlation id is carried through so the processor
 * derives it consistently
 *
 * <p>For any {@link InboundMessage}, the reconstructed {@link EventEnvelope} has {@code correlationId}
 * equal to the message {@code correlationId} and {@code eventId} equal to the message
 * {@code messageId}; consequently the {@code ActionProcessor}'s derived Correlation_Id equals the
 * message {@code correlationId} when it is non-null and equals the message {@code messageId} (the
 * {@code eventId}) when the {@code correlationId} is null.
 *
 * <p>Messages are generated directly via the {@link InboundMessage} record constructor with a valid
 * scope ({@code organization_id} always present, {@code facility_id} optionally null) and the
 * required metadata headers, mirroring {@code InboundRecordingService.toInboundMessage}, so that
 * {@link EnvelopeReconstructor#reconstruct(InboundMessage)} succeeds and the reconstructed envelope
 * can be inspected. The {@code correlationId} is generated both non-null (a fresh UUID, kept distinct
 * from {@code messageId}) and null so both branches of Requirements 7.1 and 7.2 run.
 *
 * <p>**Validates: Requirements 7.1, 7.2**
 */
class CorrelationIdDerivationPropertyTest {

    private final EnvelopeReconstructor reconstructor = new EnvelopeReconstructor();

    /**
     * For any InboundMessage, the reconstructed envelope carries the message correlationId through
     * unchanged (Req 7.1 / 7.2) and sets eventId to the message messageId; the Correlation_Id the
     * ActionProcessor derives therefore equals correlationId when non-null, else the messageId.
     *
     * <p>**Validates: Requirements 7.1, 7.2**
     */
    @Property(tries = 100)
    void correlationId_carriedThrough_elseDerivedFromEventId(
            @ForAll("inboundMessages") InboundMessage message) {

        EventEnvelope reconstructed = reconstructor.reconstruct(message);

        // Req 7.1 / 7.2: correlationId is carried through unchanged (null stays null, no sentinel).
        assertThat(reconstructed.correlationId())
                .as("reconstructed correlationId equals the message correlationId")
                .isEqualTo(message.correlationId());

        // Req 7.2: eventId is the preserved messageId so the processor can derive from it.
        assertThat(reconstructed.eventId())
                .as("reconstructed eventId equals the message messageId")
                .isEqualTo(message.messageId());

        // The Correlation_Id the ActionProcessor derives: correlationId when non-null, else eventId.
        UUID derivedCorrelationId = reconstructed.correlationId() != null
                ? reconstructed.correlationId()
                : reconstructed.eventId();

        UUID expected = message.correlationId() != null
                ? message.correlationId()
                : message.messageId();

        assertThat(derivedCorrelationId)
                .as("derived Correlation_Id is correlationId when non-null, else the messageId")
                .isEqualTo(expected);
    }

    // ── Arbitraries ──

    /**
     * InboundMessages with a valid scope + required headers so reconstruction succeeds. The
     * {@code correlationId} is toggled non-null / null so both Req 7.1 and Req 7.2 branches run;
     * when non-null it is kept distinct from {@code messageId} so the two branches are
     * distinguishable in the assertion.
     */
    @Provide
    Arbitrary<InboundMessage> inboundMessages() {
        Arbitrary<Boolean> hasCorrelationId = Arbitraries.of(true, false);
        Arbitrary<Boolean> hasFacility = Arbitraries.of(true, false);

        return Combinators.combine(
                        uuids(),              // messageId
                        eventTypes(),         // eventType
                        payloads(),           // payload
                        uuids(),              // organizationId
                        hasFacility,          // facility present?
                        hasCorrelationId)     // correlationId present?
                .as((messageId, eventType, payload, organizationId, facilityPresent,
                        correlationPresent) -> {

                    LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
                    scope.put("organization_id", organizationId);
                    if (facilityPresent) {
                        scope.put("facility_id", UUID.randomUUID());
                    } else {
                        scope.put("facility_id", null); // null preserved, no sentinel
                    }

                    LinkedHashMap<String, String> headers = validHeaders();

                    // Keep correlationId distinct from messageId so the non-null branch is
                    // unambiguously distinct from the eventId-fallback branch.
                    UUID correlationId = correlationPresent ? UUID.randomUUID() : null;

                    return new InboundMessage(
                            messageId, eventType, payload, scope, correlationId, headers);
                });
    }

    /** The required metadata headers, mirroring InboundRecordingService.toInboundMessage. */
    private static LinkedHashMap<String, String> validHeaders() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        headers.put("entity_type", "Lead");
        headers.put("entity_id", UUID.randomUUID().toString());
        headers.put("actor_type", ActorType.SYSTEM.name());
        return headers;
    }

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> eventTypes() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(40);
    }

    @Provide
    Arbitrary<Map<String, Object>> payloads() {
        Arbitrary<String> keys = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12);
        Arbitrary<Object> values = Arbitraries.oneOf(
                Arbitraries.strings().alpha().numeric().ofMaxLength(20).map(s -> (Object) s),
                Arbitraries.integers().between(-10_000, 10_000).map(i -> (Object) i),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(b -> (Object) b));
        return Arbitraries.maps(keys, values).ofMinSize(0).ofMaxSize(6)
                .map(LinkedHashMap::new);
    }
}
