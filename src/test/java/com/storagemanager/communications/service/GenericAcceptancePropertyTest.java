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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.PropertyDefaults;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 9 — Generic acceptance independent of {@code eventType}</b>.
 *
 * <p><b>Feature: messaging, Property 9: Generic acceptance independent of eventType</b> — for any
 * non-empty {@code eventType} string on an otherwise-valid envelope, the Ingest_Endpoint accepts
 * and records the message identically; there is no branch on, and no requirement of, any specific
 * {@code eventType} value.
 *
 * <p><b>Validates: Requirements 3.2, 3.3</b>
 *
 * <h2>Approach</h2>
 * This is a <em>unit-level</em> property on {@link InboundRecordingService} — the cleanest way to
 * prove "no {@code eventType} branching" without booting the web layer on every try (mirroring the
 * mocked-inbox pattern used by tasks 10.2 / 10.3). We mock {@link IdempotentInbox}, stub its
 * enriched {@code processOnce(InboundMessage, Consumer)} overload to a no-op, and capture the
 * {@link InboundMessage} the service maps the envelope onto.
 *
 * <p>For <em>any</em> non-empty {@code eventType} (jqwik strings of min length 1, including odd
 * characters, dots, and unicode letters) and arbitrary identifiers, the service always:
 * <ul>
 *   <li>invokes {@code processOnce} <b>exactly once</b> — i.e. the message is accepted and recorded
 *       regardless of the {@code eventType} value (Req 3.2 accept, Req 3.3 no branching); and</li>
 *   <li>carries the {@code eventType} through <b>verbatim</b> onto the {@link InboundMessage}, so
 *       the value is treated opaquely and never interpreted; and</li>
 *   <li>keys the inbox {@code message_id} off the envelope {@code eventId} (the Idempotency_Key),
 *       identically across all {@code eventType}s.</li>
 * </ul>
 * Taken together these assertions show acceptance + recording is identical for any {@code eventType}
 * and the value is passed through opaquely with no branching. The HTTP {@code 200} path for
 * representative {@code eventType}s is covered end-to-end by task 11.3's MockMvc test, so this
 * property stays at the recording-service level for speed.
 */
@PropertyDefaults(tries = 200, generation = GenerationMode.RANDOMIZED)
class GenericAcceptancePropertyTest {

    /**
     * Feature: messaging, Property 9: Generic acceptance independent of eventType.
     *
     * <p>For any non-empty {@code eventType} string on an otherwise-valid envelope, the recording
     * service accepts and records the message identically: {@code processOnce} is invoked exactly
     * once, the mapped {@link InboundMessage} carries the {@code eventType} verbatim, and the
     * {@code message_id} equals the envelope {@code eventId}.
     *
     * <p><b>Validates: Requirements 3.2, 3.3</b>
     */
    @Property
    void acceptanceAndRecordingAreIdenticalForAnyEventType(
            @ForAll("nonEmptyEventTypes") String eventType) {

        // Mock the starter inbox; stub the enriched processOnce overload to perform no work so we
        // can capture the InboundMessage the service maps the envelope onto (no-op handler).
        IdempotentInbox inbox = mock(IdempotentInbox.class);
        doNothing().when(inbox).processOnce(any(InboundMessage.class), any());
        InboundRecordingService service = new InboundRecordingService(inbox, m -> { /* no-op seam */ });

        EventEnvelope envelope = envelopeWithEventType(eventType);

        // Resolve the authoritative org as a Trusted_Ingest_Caller would (envelope.organizationId).
        service.record(envelope, envelope.organizationId());

        // --- Acceptance + recording happened exactly once, regardless of eventType (Req 3.2). ---
        @SuppressWarnings("unchecked")
        ArgumentCaptor<InboundMessage> captor = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inbox, times(1)).processOnce(captor.capture(), any(Consumer.class));
        InboundMessage recorded = captor.getValue();

        // --- No branching: the eventType is carried through verbatim, treated opaquely (Req 3.3). ---
        assertThat(recorded.eventType())
                .as("eventType is carried through opaquely, verbatim, with no branching")
                .isEqualTo(eventType);

        // --- The record is keyed identically across all eventTypes: message_id == eventId. ---
        assertThat(recorded.messageId())
                .as("the recorded message_id is the envelope eventId, identical for any eventType")
                .isEqualTo(envelope.eventId());
    }

    // =====================================================================
    // Generators
    // =====================================================================

    /**
     * Any non-empty {@code eventType} string. The space mixes plain ASCII words, dotted/namespaced
     * types, and arbitrary unicode-letter strings (min length 1) so the property exercises odd,
     * non-domain {@code eventType} values and proves none of them are special-cased.
     */
    @Provide
    Arbitrary<String> nonEmptyEventTypes() {
        Arbitrary<String> arbitraryUnicode = Arbitraries.strings().ofMinLength(1).ofMaxLength(64);
        Arbitrary<String> dotted = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.0123456789")
                .ofMinLength(1)
                .ofMaxLength(48);
        Arbitrary<String> representative = Arbitraries.of(
                "SendCommunication",
                "LeadCreated",
                "CommunicationSent",
                "x",
                "event.type.with.dots",
                "\u00e9v\u00e9nement",   // unicode letters
                "123numeric-start",
                "UPPER_SNAKE_CASE");
        return Arbitraries.oneOf(arbitraryUnicode, dotted, representative);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Build an otherwise-valid 13-field envelope carrying the given {@code eventType}; the
     * remaining fields are fixed/arbitrary and irrelevant to the generic-acceptance invariant.
     */
    private EventEnvelope envelopeWithEventType(String eventType) {
        return new EventEnvelope(
                UUID.randomUUID(),                 // eventId
                eventType,                          // eventType under test (non-empty, generic)
                1,                                  // schemaVersion
                Instant.parse("2024-01-01T00:00:00Z"),
                UUID.randomUUID(),                  // organizationId
                null,                               // facilityId (nullable)
                "Entity",                           // entityType
                UUID.randomUUID(),                  // entityId
                EventEnvelope.ActorType.SYSTEM,     // actorType
                null,                               // actorId (nullable)
                null,                               // correlationId (nullable)
                null,                               // causationId (nullable)
                Map.of("k", "v"));                  // payload
    }
}
