package com.storagemanager.communications.inbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.messaging.api.InboundMessage;

/**
 * Edge-case example tests for the design's <b>Error Handling</b> / <b>Testing Strategy (3.6 edge
 * case)</b>: every required header/scope value that is absent or unparseable — and a present-but-bad
 * optional {@code actor_id}/{@code causation_id} — causes {@link EnvelopeReconstructor#reconstruct}
 * to raise a {@link ReconstructionException} naming the offending field.
 *
 * <p><b>Validates: Requirements 3.6, 3.7</b>
 *
 * <h2>Approach</h2>
 * Each test starts from an otherwise-valid {@link InboundMessage} (valid {@code organization_id}
 * scope plus valid {@code schema_version}/{@code occurred_at}/{@code entity_type}/{@code entity_id}/
 * {@code actor_type} headers), corrupts <em>exactly one</em> field, then asserts
 * {@code reconstruct(...)} throws a {@link ReconstructionException} whose {@link
 * ReconstructionException#field()} equals the offending field name. A baseline sanity test confirms
 * the otherwise-valid message reconstructs cleanly, so each failure is attributable solely to the
 * single corrupted field.
 *
 * <p>Per the design, an absent {@code occurred_at} surfaces as field {@code occurred_at} with reason
 * "not an ISO-8601 instant" (the require-then-parse helper wraps the absence), so this suite tests an
 * <em>unparseable</em> {@code occurred_at} value.
 */
class ReconstructionFieldCorruptionTest {

    private static final String ORG = "organization_id";

    private final EnvelopeReconstructor reconstructor = new EnvelopeReconstructor();

    // =====================================================================
    // Baseline — the un-corrupted message reconstructs successfully
    // =====================================================================

    @Test
    void baselineValidMessageReconstructsWithoutError() {
        InboundMessage valid = validMessage();

        // Sanity: no corruption -> no exception. Guards against false positives below.
        assertThat(reconstructor.reconstruct(valid)).isNotNull();
    }

    // =====================================================================
    // Required scope corruption
    // =====================================================================

    @Test
    void missingOrganizationIdScopeThrowsNamingOrganizationId() {
        LinkedHashMap<String, UUID> scope = validScope();
        scope.remove(ORG); // corrupt exactly one field: drop the required organization scope

        InboundMessage message = messageWith(scope, validHeaders());

        assertThatThrownBy(() -> reconstructor.reconstruct(message))
                .isInstanceOf(ReconstructionException.class)
                .extracting(ex -> ((ReconstructionException) ex).field())
                .isEqualTo(ORG);
    }

    // =====================================================================
    // Required header corruption
    // =====================================================================

    @Test
    void nonIntegerSchemaVersionThrowsNamingSchemaVersion() {
        LinkedHashMap<String, String> headers = validHeaders();
        headers.put("schema_version", "not-a-number");

        InboundMessage message = messageWith(validScope(), headers);

        assertThatThrownBy(() -> reconstructor.reconstruct(message))
                .isInstanceOf(ReconstructionException.class)
                .extracting(ex -> ((ReconstructionException) ex).field())
                .isEqualTo("schema_version");
    }

    @Test
    void unparseableOccurredAtThrowsNamingOccurredAt() {
        LinkedHashMap<String, String> headers = validHeaders();
        headers.put("occurred_at", "not-an-instant");

        InboundMessage message = messageWith(validScope(), headers);

        assertThatThrownBy(() -> reconstructor.reconstruct(message))
                .isInstanceOf(ReconstructionException.class)
                .extracting(ex -> ((ReconstructionException) ex).field())
                .isEqualTo("occurred_at");
    }

    @Test
    void blankEntityTypeThrowsNamingEntityType() {
        LinkedHashMap<String, String> headers = validHeaders();
        headers.put("entity_type", "   "); // blank is treated as absent for a required header

        InboundMessage message = messageWith(validScope(), headers);

        assertThatThrownBy(() -> reconstructor.reconstruct(message))
                .isInstanceOf(ReconstructionException.class)
                .extracting(ex -> ((ReconstructionException) ex).field())
                .isEqualTo("entity_type");
    }

    @Test
    void nonUuidEntityIdThrowsNamingEntityId() {
        LinkedHashMap<String, String> headers = validHeaders();
        headers.put("entity_id", "not-a-uuid");

        InboundMessage message = messageWith(validScope(), headers);

        assertThatThrownBy(() -> reconstructor.reconstruct(message))
                .isInstanceOf(ReconstructionException.class)
                .extracting(ex -> ((ReconstructionException) ex).field())
                .isEqualTo("entity_id");
    }

    @Test
    void badActorTypeThrowsNamingActorType() {
        LinkedHashMap<String, String> headers = validHeaders();
        headers.put("actor_type", "ROBOT"); // not a valid ActorType (SYSTEM | USER)

        InboundMessage message = messageWith(validScope(), headers);

        assertThatThrownBy(() -> reconstructor.reconstruct(message))
                .isInstanceOf(ReconstructionException.class)
                .extracting(ex -> ((ReconstructionException) ex).field())
                .isEqualTo("actor_type");
    }

    // =====================================================================
    // Present-but-bad optional header corruption
    // =====================================================================

    @Test
    void presentButBadActorIdThrowsNamingActorId() {
        LinkedHashMap<String, String> headers = validHeaders();
        headers.put("actor_id", "not-a-uuid"); // optional, but present-and-unparseable still fails

        InboundMessage message = messageWith(validScope(), headers);

        assertThatThrownBy(() -> reconstructor.reconstruct(message))
                .isInstanceOf(ReconstructionException.class)
                .extracting(ex -> ((ReconstructionException) ex).field())
                .isEqualTo("actor_id");
    }

    @Test
    void presentButBadCausationIdThrowsNamingCausationId() {
        LinkedHashMap<String, String> headers = validHeaders();
        headers.put("causation_id", "not-a-uuid"); // optional, but present-and-unparseable fails

        InboundMessage message = messageWith(validScope(), headers);

        assertThatThrownBy(() -> reconstructor.reconstruct(message))
                .isInstanceOf(ReconstructionException.class)
                .extracting(ex -> ((ReconstructionException) ex).field())
                .isEqualTo("causation_id");
    }

    // =====================================================================
    // Fixtures — a valid baseline, mutated by exactly one field per test
    // =====================================================================

    /** A valid organization-only scope (facility is optional and omitted). */
    private static LinkedHashMap<String, UUID> validScope() {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put(ORG, UUID.randomUUID());
        return scope;
    }

    /** The full set of valid, parseable required headers (no optional actor_id/causation_id). */
    private static LinkedHashMap<String, String> validHeaders() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        headers.put("entity_type", "lead");
        headers.put("entity_id", UUID.randomUUID().toString());
        headers.put("actor_type", ActorType.SYSTEM.name());
        return headers;
    }

    /** An otherwise-valid message (valid scope + valid headers) that reconstructs cleanly. */
    private static InboundMessage validMessage() {
        return messageWith(validScope(), validHeaders());
    }

    private static InboundMessage messageWith(LinkedHashMap<String, UUID> scope,
            LinkedHashMap<String, String> headers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "SendCommunication");
        return new InboundMessage(
                UUID.randomUUID(),      // messageId
                "SendCommunication",    // eventType
                payload,
                scope,
                UUID.randomUUID(),      // correlationId
                headers);
    }
}
