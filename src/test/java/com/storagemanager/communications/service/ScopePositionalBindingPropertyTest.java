package com.storagemanager.communications.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.PropertyDefaults;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 7 — Scope positional binding</b>.
 *
 * <p><b>Feature: messaging, Property 7: Scope positional binding</b> — for any resolved
 * Organization_Scope and any optional Facility_Scope, the recorded inbox/outbox rows persist
 * {@code organization_id} to {@code scope_0} and {@code facility_id} to {@code scope_1} per the
 * declared {@code scope-fields} order; an absent {@code facilityId} yields a {@code scope_1} null
 * with no sentinel substituted.
 *
 * <p><b>Validates: Requirements 1.5, 9.1, 9.2, 9.3, 9.4</b>
 *
 * <h2>Approach</h2>
 * This is a <em>unit-level</em> property that exercises the positional binding
 * {@link InboundRecordingService} performs when it maps a validated {@link EventEnvelope} onto the
 * starter {@link InboundMessage}. The {@code InboundMessage.scope()} is a {@link LinkedHashMap}, so
 * its key iteration order is the observable positional binding <em>before</em> persistence: slot 0
 * (the {@code scope_0} column) is the first key, slot 1 (the {@code scope_1} column) is the second
 * key. We mock {@link IdempotentInbox} (a concrete starter class whose {@code processOnce} method is
 * non-final and therefore mockable via the Mockito on the {@code spring-boot-starter-test}
 * classpath), stub {@code processOnce(InboundMessage, Consumer)} to do nothing, and capture the
 * {@link InboundMessage} the service passes.
 *
 * <p>Property 7 also names the outbox rows; in this base scope the inbound mapping done by
 * {@link InboundRecordingService} is the authoritative place scope is assembled. The symmetric
 * outbox-side positional binding (the enriched {@code OutboxWriter} persisting {@code scope_0}/
 * {@code scope_1}) is exercised by the starter's {@code OutboxWriter} and covered by task 13.1's
 * {@code @DataJpaTest} ({@code outbox} row {@code scope_0}/{@code scope_1}). This property keeps its
 * focus on {@link InboundRecordingService}'s scope assembly.
 */
@PropertyDefaults(tries = 200, generation = GenerationMode.RANDOMIZED)
class ScopePositionalBindingPropertyTest {

    /**
     * Feature: messaging, Property 7: Scope positional binding.
     *
     * <p>For any resolved Organization_Scope and any optional Facility_Scope, the recorded
     * {@link InboundMessage} binds {@code organization_id} to slot 0 and {@code facility_id} to
     * slot 1 in declared {@code scope-fields} order; an absent {@code facilityId} leaves slot 1
     * null with no sentinel substituted.
     *
     * <p><b>Validates: Requirements 1.5, 9.1, 9.2, 9.3, 9.4</b>
     */
    @Property
    void scopeBindsOrganizationToSlot0AndFacilityToSlot1(
            @ForAll("organizationScopes") UUID organizationScope,
            @ForAll("optionalFacilityIds") UUID facilityId) {

        // Mock the starter inbox; capture the InboundMessage passed to the enriched processOnce
        // overload and stub it to perform no work (the no-op handler never runs here).
        IdempotentInbox inbox = mock(IdempotentInbox.class);
        doNothing().when(inbox).processOnce(any(InboundMessage.class), any());
        InboundRecordingService service = new InboundRecordingService(inbox, m -> { /* no-op seam */ });

        EventEnvelope envelope = envelopeWith(organizationScope, facilityId);

        // The resolved Organization_Scope is the authoritative org on ingest (Req 9.1); here it
        // equals the envelope organizationId (as a Trusted_Ingest_Caller would resolve it).
        service.record(envelope, organizationScope);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<InboundMessage> captor = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inbox).processOnce(captor.capture(), any(Consumer.class));
        InboundMessage recorded = captor.getValue();

        LinkedHashMap<String, UUID> scope = recorded.scope();

        // --- Declared scope-fields order: slot 0 is organization_id, slot 1 is facility_id. ---
        List<String> keys = new ArrayList<>(scope.keySet());
        assertThat(keys)
                .as("scope key iteration order binds organization_id -> scope_0, facility_id -> scope_1")
                .containsExactly("organization_id", "facility_id");

        // --- scope_0 holds the resolved Organization_Scope (Req 1.5, 9.1). ---
        assertThat(scope.get("organization_id"))
                .as("scope_0 persists the resolved organization scope")
                .isEqualTo(organizationScope);

        // --- scope_1 holds the Facility_Scope, or null (no sentinel) when absent (Req 9.2, 9.3). ---
        assertThat(scope.get("facility_id"))
                .as("scope_1 persists the facility scope exactly, or null with no sentinel when absent")
                .isEqualTo(facilityId);
        if (facilityId == null) {
            assertThat(scope.get("facility_id"))
                    .as("absent facilityId -> scope_1 null, no sentinel substituted (Req 9.3)")
                    .isNull();
        }

        // --- Scope ties to the right record: the inbox message_id is the envelope eventId. ---
        assertThat(recorded.messageId())
                .as("the recorded message_id is the envelope eventId (ties scope to the record)")
                .isEqualTo(envelope.eventId());
    }

    // =====================================================================
    // Generators
    // =====================================================================

    /** Any resolved Organization_Scope UUID. */
    @Provide
    Arbitrary<UUID> organizationScopes() {
        return uuids();
    }

    /** An optional Facility_Scope: a UUID present ~75% of the time, null the rest (absent facility). */
    @Provide
    Arbitrary<UUID> optionalFacilityIds() {
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, uuids()),
                net.jqwik.api.Tuple.of(1, Arbitraries.just((UUID) null)));
    }

    private Arbitrary<UUID> uuids() {
        Arbitrary<Long> hi = Arbitraries.longs();
        Arbitrary<Long> lo = Arbitraries.longs();
        return Combinators.combine(hi, lo).as(UUID::new);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Build an otherwise-valid 13-field envelope for the given organization and (optional)
     * facility; the remaining fields are irrelevant to the scope positional binding under test.
     */
    private EventEnvelope envelopeWith(UUID organizationId, UUID facilityId) {
        return new EventEnvelope(
                UUID.randomUUID(),                 // eventId
                "GenericEvent",                    // eventType (generic; not branched on)
                1,                                  // schemaVersion
                Instant.parse("2024-01-01T00:00:00Z"),
                organizationId,
                facilityId,                         // nullable
                "Entity",                           // entityType
                UUID.randomUUID(),                  // entityId
                EventEnvelope.ActorType.SYSTEM,     // actorType
                null,                               // actorId (nullable)
                null,                               // correlationId (nullable)
                null,                               // causationId (nullable)
                Map.of("k", "v"));                  // payload
    }
}
