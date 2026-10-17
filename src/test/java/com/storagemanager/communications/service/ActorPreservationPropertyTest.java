package com.storagemanager.communications.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property tests for {@link InboundRecordingService} (messaging spec, Task 10.3).
 *
 * <p><strong>Feature: messaging, Property 11: Actor preservation on persistence.</strong>
 * For any {@code actorType} and optional {@code actorId}, the recorded inbox headers preserve
 * {@code actor_type} / {@code actor_id} exactly as carried on the envelope and never substitute
 * the transport identity of a {@code Trusted_Ingest_Caller}. The service maps the envelope onto a
 * starter {@link InboundMessage}; this test captures that message (via a mocked
 * {@link IdempotentInbox} and an {@link ArgumentCaptor}) and asserts the actor passthrough headers
 * reflect the envelope's own {@code actorType} / {@code actorId} and nothing else.
 *
 * <p>Validates: Requirements 4.6
 */
class ActorPreservationPropertyTest {

    // ------------------------------------------------------------------------
    // Property 11: recorded headers preserve the envelope actor exactly (Req 4.6)
    // ------------------------------------------------------------------------

    /**
     * For an arbitrary {@code actorType} (SYSTEM | USER) and a nullable {@code actorId}, the
     * captured {@link InboundMessage} carries:
     * <ul>
     *   <li>{@code actor_type} header equal to {@code envelope.actorType().name()}; and</li>
     *   <li>{@code actor_id} header equal to {@code actorId.toString()} when present, or the
     *       {@code actor_id} key entirely absent when the envelope carries no actorId (no sentinel,
     *       per {@link InboundRecordingService} which only puts {@code actor_id} when non-null).</li>
     * </ul>
     * This proves the actor is preserved from the envelope and never overwritten by the transport
     * identity of a Trusted_Ingest_Caller.
     *
     * <p>Validates: Requirements 4.6
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void recordedHeaders_preserveEnvelopeActor(
            @ForAll("actorTypes") ActorType actorType,
            @ForAll("nullableActorIds") UUID actorId) {

        // Mocked starter inbox: stub the enriched processOnce to do nothing so we can capture the
        // mapped InboundMessage the service hands to it.
        IdempotentInbox inbox = mock(IdempotentInbox.class);
        doNothing().when(inbox).processOnce(any(InboundMessage.class), any());

        InboundRecordingService service = new InboundRecordingService(inbox, m -> { /* no-op seam */ });

        EventEnvelope envelope = envelopeWithActor(actorType, actorId);

        // record uses the envelope's own organizationId as the authoritative scope here; the actor
        // headers are independent of the resolved org and the caller's transport identity.
        service.record(envelope, envelope.organizationId());

        ArgumentCaptor<InboundMessage> captor = ArgumentCaptor.forClass(InboundMessage.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<InboundMessage>> handlerCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(inbox).processOnce(captor.capture(), handlerCaptor.capture());

        InboundMessage captured = captor.getValue();

        // actor_type is always preserved exactly as the envelope's actorType name.
        assertThat(captured.headers().get("actor_type")).isEqualTo(actorType.name());

        if (actorId != null) {
            // actor_id present -> preserved verbatim as its string form.
            assertThat(captured.headers().get("actor_id")).isEqualTo(actorId.toString());
        } else {
            // actor_id absent -> no key, no sentinel substituted.
            assertThat(captured.headers()).doesNotContainKey("actor_id");
        }
    }

    // ------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------

    @Provide
    Arbitrary<ActorType> actorTypes() {
        return Arbitraries.of(ActorType.SYSTEM, ActorType.USER);
    }

    /** A UUID actorId that is present roughly half the time and null the rest (nullable contract). */
    @Provide
    Arbitrary<UUID> nullableActorIds() {
        return Arbitraries.create(UUID::randomUUID).injectNull(0.5);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static EventEnvelope envelopeWithActor(ActorType actorType, UUID actorId) {
        return new EventEnvelope(
                UUID.randomUUID(),      // eventId
                "GenericEvent",         // eventType
                1,                      // schemaVersion
                Instant.parse("2024-01-01T00:00:00Z"),
                UUID.randomUUID(),      // organizationId
                null,                   // facilityId
                "Lead",                 // entityType
                UUID.randomUUID(),      // entityId
                actorType,
                actorId,
                null,                   // correlationId
                null,                   // causationId
                Map.of());              // payload
    }
}
