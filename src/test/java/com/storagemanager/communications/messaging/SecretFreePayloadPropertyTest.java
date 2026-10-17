package com.storagemanager.communications.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.communications.service.InboundRecordingService;
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based test for secret-free enriched payloads.
 *
 * <p>Feature: messaging, Property 12: Secret-free enriched payloads — for arbitrary payloads
 * including denylisted secret/PII keys, the messaging base adds no payment secret, auth secret,
 * or PII to the enriched/emitted payload and the secret-free guard flags any denylisted key.
 *
 * <p>The property is realized in two halves, matching the domain-agnostic nature of the base:
 *
 * <ul>
 *   <li><b>Property A (the mechanics add nothing):</b> an arbitrary <em>clean</em> payload (no
 *       denylisted keys) is mapped through the real
 *       {@link InboundRecordingService#record(EventEnvelope, UUID)} envelope -&gt;
 *       {@link InboundMessage} transform. The recorded {@code InboundMessage} is captured via a
 *       Mockito-mocked {@link IdempotentInbox} and an {@link ArgumentCaptor}. The captured
 *       payload's key set must <em>equal</em> the original payload's key set — the mechanics
 *       introduce no new key, and in particular no secret/PII key. (Payload values are passed
 *       through unchanged, so if no secret/PII key is present on input, none is present on output.)
 *   <li><b>Property B (the guard flags denylisted keys):</b> for a payload that contains at least
 *       one denylisted key (mixed with arbitrary clean keys),
 *       {@link PayloadSecretGuard#findDenylistedKeys(Map)} returns a non-empty set containing the
 *       injected denylisted key(s); for an all-clean payload it returns the empty set.
 * </ul>
 *
 * **Validates: Requirements 10.2**
 */
class SecretFreePayloadPropertyTest {

    /**
     * The documented denylist key names (mirrors {@link PayloadSecretGuard}'s denylist, in the
     * casing a producer would naturally emit). Used both to seed Property B and to filter clean
     * payloads for Property A.
     */
    private static final String[] DENYLISTED_KEYS = {
            "password", "secret", "token", "authorization", "apiKey", "api_key",
            "creditCard", "cardNumber", "cvv", "ssn", "dateOfBirth", "dob", "email",
            "firstName", "lastName", "phone", "mobilePhone", "homePhone",
            "addressLine1", "addressLine2"
    };

    // ── Arbitraries ──────────────────────────────────────────────────────────

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    /** JSON-simple payload values so key-set equality and value passthrough are unambiguous. */
    private Arbitrary<Object> values() {
        return Arbitraries.oneOf(
                Arbitraries.strings().alpha().numeric().ofMaxLength(20).map(s -> (Object) s),
                Arbitraries.integers().between(-10_000, 10_000).map(i -> (Object) i),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(b -> (Object) b));
    }

    /**
     * Arbitrary CLEAN payloads: keys are alphabetic identifiers guaranteed NOT to collide with any
     * denylisted key name (case- and separator-insensitively). This isolates Property A to the
     * "mechanics add nothing" invariant without any secret/PII key present on input.
     */
    @Provide
    Arbitrary<Map<String, Object>> cleanPayloads() {
        Arbitrary<String> cleanKeys = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12)
                .filter(k -> !isDenylisted(k));
        return Arbitraries.maps(cleanKeys, values()).ofMinSize(0).ofMaxSize(6)
                .map(LinkedHashMap::new);
    }

    /** Full envelope carrying an arbitrary clean payload (Property A). */
    @Provide
    Arbitrary<EventEnvelope> envelopesWithCleanPayload() {
        return Combinators.combine(uuids(), uuids(), uuids(), cleanPayloads())
                .as((eventId, organizationId, entityId, payload) -> new EventEnvelope(
                        eventId,
                        "GenericEvent",
                        1,
                        Instant.parse("2024-01-01T00:00:00Z"),
                        organizationId,
                        null,            // no facility
                        "Generic",
                        entityId,
                        ActorType.SYSTEM,
                        null, null, null,
                        payload));
    }

    /** One of the documented denylisted key names (Property B seed). */
    @Provide
    Arbitrary<String> denylistedKeys() {
        return Arbitraries.of(DENYLISTED_KEYS);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Property A: the mapping mechanics introduce no new payload key
    //             (in particular, no secret/PII key)
    // ═══════════════════════════════════════════════════════════════════════

    @Property(tries = 100)
    void mappingMechanicsAddNoPayloadKey(@ForAll("envelopesWithCleanPayload") EventEnvelope envelope) {
        IdempotentInbox inbox = mock(IdempotentInbox.class);
        doNothing().when(inbox).processOnce(any(InboundMessage.class), any());

        InboundRecordingService service = new InboundRecordingService(inbox, m -> { /* no-op seam */ });
        service.record(envelope, envelope.organizationId());

        ArgumentCaptor<InboundMessage> captor = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inbox).processOnce(captor.capture(), any());
        InboundMessage recorded = captor.getValue();

        // The mechanics carry the payload opaquely: the recorded key set EQUALS the original key
        // set, so no key (and therefore no secret/PII key) is introduced by the base.
        assertThat(recorded.payload().keySet()).isEqualTo(envelope.payload().keySet());

        // Belt-and-suspenders: since the input was clean, the recorded payload is secret-free too.
        assertThat(PayloadSecretGuard.findDenylistedKeys(recorded.payload())).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Property B: the secret-free guard flags any denylisted key
    // ═══════════════════════════════════════════════════════════════════════

    @Property(tries = 100)
    void guardFlagsInjectedDenylistedKey(
            @ForAll("denylistedKeys") String secretKey,
            @ForAll("cleanPayloads") Map<String, Object> cleanKeys) {

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>(cleanKeys);
        // Inject at least one denylisted secret/PII key (overwrites any clean key of same name,
        // which cannot happen because cleanPayloads excludes denylisted names).
        payload.put(secretKey, "sensitive-value");

        Set<String> flagged = PayloadSecretGuard.findDenylistedKeys(payload);

        // The guard flags the injected denylisted key (non-empty, contains the injected key).
        assertThat(flagged).isNotEmpty();
        assertThat(flagged).contains(secretKey);
        assertThat(PayloadSecretGuard.isSecretFree(payload)).isFalse();
    }

    @Property(tries = 100)
    void guardReportsCleanPayloadAsSecretFree(@ForAll("cleanPayloads") Map<String, Object> cleanPayload) {
        assertThat(PayloadSecretGuard.findDenylistedKeys(cleanPayload)).isEmpty();
        assertThat(PayloadSecretGuard.isSecretFree(cleanPayload)).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Mirrors {@link PayloadSecretGuard}'s normalization to decide whether a key would be flagged. */
    private static boolean isDenylisted(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
        for (String denied : DENYLISTED_KEYS) {
            String n = denied.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
            if (n.equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
