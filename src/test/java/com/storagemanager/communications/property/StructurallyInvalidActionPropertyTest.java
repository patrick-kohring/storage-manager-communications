package com.storagemanager.communications.property;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.action.support.ActionArbitraries;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.action.support.HarnessFixture;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.RecipientType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property test for the structural-rejection behavior of the {@code ActionProcessor}
 * (lead-communication-action spec, Task 7.3).
 *
 * <p>Feature: lead-communication-action, Property 6: Structurally invalid actions are rejected
 *
 * <p>For any action payload that omits a required field ({@code entityKind}, {@code leadId}, or
 * {@code templateName}), carries an {@code entityKind} other than {@code LEAD}, carries a
 * {@code leadId} that is not a syntactically valid UUID, or cannot be parsed into a
 * {@code SendCommunicationAction}, processing rejects the action with a processing error identifying
 * the offending condition and invokes the send seam zero times.
 *
 * <p>The processor is driven against the shared harness
 * ({@link com.storagemanager.communications.action.support}) with no template / mapping / lead
 * seeded: every case here is rejected during envelope parse or action validation, well before the
 * lead-read, template-resolution, or dispatch stages, so no seeding is required. Each case supplies
 * an otherwise-valid single-recipient selection (a {@code recipientType} of {@code LEAD}) so the sole
 * cause of rejection is the structural defect under test — except where the defect under test is the
 * recipient fields themselves (not exercised here; that is Property 5).
 *
 * <p>Payloads are built with the shared {@link ActionEnvelopes} helper: structurally-present-but-wrong
 * payloads via {@link ActionEnvelopes#payloadMap} / {@link ActionEnvelopes#envelopeWithPayload} with
 * a hand-built map, and omitted-field payloads via the same {@code payloadMap} (a {@code null} field
 * is genuinely absent from the map, not bound to a present {@code null} key).
 *
 * <p>Validates: Requirements 1.2, 1.7, 1.8, 2.3
 */
class StructurallyInvalidActionPropertyTest {

    // ------------------------------------------------------------------------
    // Missing required field: entityKind (Req 1.7)
    // ------------------------------------------------------------------------

    /**
     * For any payload omitting {@code entityKind}, processing rejects the action naming the missing
     * {@code entityKind} field and invokes the send seam zero times.
     *
     * <p>Validates: Requirements 1.7
     */
    @Property(tries = 100)
    void missingEntityKind_isRejected(@ForAll("missingEntityKind") EventEnvelope envelope) {
        HarnessFixture harness = new HarnessFixture();

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("omitting entityKind is rejected (Req 1.7)")
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining("entityKind");

        assertThat(harness.send().invocationCount())
                .as("send seam invoked zero times on structural rejection")
                .isZero();
    }

    @Provide
    Arbitrary<EventEnvelope> missingEntityKind() {
        // entityKind omitted; all other required/selection fields present and valid.
        return Combinators.combine(ActionArbitraries.templateName(), eventId(), maybeCorrelation())
                .as((templateName, eventId, corr) -> ActionEnvelopes.envelopeFor(
                        null,                               // entityKind omitted
                        UUID.randomUUID().toString(),       // valid leadId
                        templateName,                       // valid templateName
                        RecipientType.LEAD,                 // valid single-recipient selection
                        null,
                        eventId, corr));
    }

    // ------------------------------------------------------------------------
    // Missing required field: leadId (Req 1.7)
    // ------------------------------------------------------------------------

    /**
     * For any payload omitting {@code leadId}, processing rejects the action naming the missing
     * {@code leadId} field and invokes the send seam zero times.
     *
     * <p>Validates: Requirements 1.7
     */
    @Property(tries = 100)
    void missingLeadId_isRejected(@ForAll("missingLeadId") EventEnvelope envelope) {
        HarnessFixture harness = new HarnessFixture();

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("omitting leadId is rejected (Req 1.7)")
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining("leadId");

        assertThat(harness.send().invocationCount())
                .as("send seam invoked zero times on structural rejection")
                .isZero();
    }

    @Provide
    Arbitrary<EventEnvelope> missingLeadId() {
        return Combinators.combine(ActionArbitraries.templateName(), eventId(), maybeCorrelation())
                .as((templateName, eventId, corr) -> ActionEnvelopes.envelopeFor(
                        EntityType.LEAD,
                        null,                               // leadId omitted
                        templateName,
                        RecipientType.LEAD,
                        null,
                        eventId, corr));
    }

    // ------------------------------------------------------------------------
    // Missing required field: templateName (Req 1.7)
    // ------------------------------------------------------------------------

    /**
     * For any payload omitting {@code templateName}, processing rejects the action naming the missing
     * {@code templateName} field and invokes the send seam zero times.
     *
     * <p>Validates: Requirements 1.7
     */
    @Property(tries = 100)
    void missingTemplateName_isRejected(@ForAll("missingTemplateName") EventEnvelope envelope) {
        HarnessFixture harness = new HarnessFixture();

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("omitting templateName is rejected (Req 1.7)")
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining("templateName");

        assertThat(harness.send().invocationCount())
                .as("send seam invoked zero times on structural rejection")
                .isZero();
    }

    @Provide
    Arbitrary<EventEnvelope> missingTemplateName() {
        return Combinators.combine(eventId(), maybeCorrelation())
                .as((eventId, corr) -> ActionEnvelopes.envelopeFor(
                        EntityType.LEAD,
                        UUID.randomUUID().toString(),
                        null,                               // templateName omitted
                        RecipientType.LEAD,
                        null,
                        eventId, corr));
    }

    // ------------------------------------------------------------------------
    // entityKind other than LEAD (Req 1.2 / 2.3)
    // ------------------------------------------------------------------------

    /**
     * For any payload carrying an {@code entityKind} string other than {@code LEAD}, processing
     * rejects the action (the unknown enum string fails binding to the single-valued
     * {@link EntityType}, so it surfaces as a malformed-payload rejection — Req 1.2 via Req 2.3) and
     * invokes the send seam zero times.
     *
     * <p>Validates: Requirements 1.2, 2.3
     */
    @Property(tries = 100)
    void nonLeadEntityKind_isRejected(@ForAll("nonLeadEntityKind") EventEnvelope envelope) {
        HarnessFixture harness = new HarnessFixture();

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("an entityKind other than LEAD is rejected (Req 1.2 / 2.3)")
                .isInstanceOf(ActionValidationException.class);

        assertThat(harness.send().invocationCount())
                .as("send seam invoked zero times on structural rejection")
                .isZero();
    }

    @Provide
    Arbitrary<EventEnvelope> nonLeadEntityKind() {
        // entityKind present but not "LEAD": an unsupported/unknown kind. EntityType is single-valued
        // (LEAD), so any other string is an unknown enum literal that fails Jackson binding and is
        // reported as a malformed payload — the offending condition is identified.
        Arbitrary<String> otherKind = Arbitraries.of(
                "TENANT", "OWNER", "FACILITY", "ORGANIZATION", "UNIT", "lead", "Lead", "LEADX", "");
        return Combinators.combine(otherKind, ActionArbitraries.templateName(), eventId(), maybeCorrelation())
                .as((kind, templateName, eventId, corr) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("entityKind", kind);                       // non-LEAD literal
                    payload.put("leadId", UUID.randomUUID().toString());
                    payload.put("templateName", templateName);
                    payload.put("recipientType", RecipientType.LEAD.name());
                    return ActionEnvelopes.envelopeWithPayload(payload, eventId, corr);
                });
    }

    // ------------------------------------------------------------------------
    // leadId that is not a syntactically valid UUID (Req 1.8)
    // ------------------------------------------------------------------------

    /**
     * For any payload carrying a {@code leadId} that is not a syntactically valid UUID, processing
     * rejects the action naming the {@code leadId} field and invokes the send seam zero times.
     *
     * <p>Validates: Requirements 1.8
     */
    @Property(tries = 100)
    void invalidUuidLeadId_isRejected(@ForAll("invalidUuidLeadId") EventEnvelope envelope) {
        HarnessFixture harness = new HarnessFixture();

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("a non-UUID leadId is rejected naming the leadId field (Req 1.8)")
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining("leadId");

        assertThat(harness.send().invocationCount())
                .as("send seam invoked zero times on structural rejection")
                .isZero();
    }

    @Provide
    Arbitrary<EventEnvelope> invalidUuidLeadId() {
        return Combinators.combine(nonUuidString(), ActionArbitraries.templateName(),
                        eventId(), maybeCorrelation())
                .as((badLeadId, templateName, eventId, corr) -> ActionEnvelopes.envelopeFor(
                        EntityType.LEAD,
                        badLeadId,                          // present but not a valid UUID
                        templateName,
                        RecipientType.LEAD,
                        null,
                        eventId, corr));
    }

    // ------------------------------------------------------------------------
    // Payload that cannot be parsed into a SendCommunicationAction (Req 2.3)
    // ------------------------------------------------------------------------

    /**
     * For any payload whose fields cannot bind to {@code SendCommunicationAction} (a field carries a
     * JSON value of an incompatible type), processing rejects the action as a malformed payload and
     * invokes the send seam zero times.
     *
     * <p>Validates: Requirements 2.3
     */
    @Property(tries = 100)
    void unparseablePayload_isRejected(@ForAll("unparseablePayload") EventEnvelope envelope) {
        HarnessFixture harness = new HarnessFixture();

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("a payload that cannot bind to SendCommunicationAction is rejected (Req 2.3)")
                .isInstanceOf(ActionValidationException.class);

        assertThat(harness.send().invocationCount())
                .as("send seam invoked zero times on structural rejection")
                .isZero();
    }

    @Provide
    Arbitrary<EventEnvelope> unparseablePayload() {
        // A String-typed field given a structured JSON value (object or array) cannot bind to the
        // record's String components, so convertValue throws and the processor reports a malformed
        // payload. We mutate one of the String fields (templateName / leadId / explicitRecipient) to a
        // non-scalar value while keeping the rest well-formed.
        Arbitrary<String> whichField = Arbitraries.of("templateName", "leadId", "explicitRecipient");
        Arbitrary<Object> nonScalar = Arbitraries.oneOf(
                Arbitraries.just(Map.of("nested", "object")),
                Arbitraries.just(java.util.List.of("array", "value")));
        return Combinators.combine(whichField, nonScalar, eventId(), maybeCorrelation())
                .as((field, badValue, eventId, corr) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("entityKind", EntityType.LEAD.name());
                    payload.put("leadId", UUID.randomUUID().toString());
                    payload.put("templateName", "valid-template");
                    payload.put("recipientType", RecipientType.LEAD.name());
                    // Overwrite the chosen String field with a non-scalar value that cannot bind.
                    payload.put(field, badValue);
                    return ActionEnvelopes.envelopeWithPayload(payload, eventId, corr);
                });
    }

    // ------------------------------------------------------------------------
    // Local building-block generators
    // ------------------------------------------------------------------------

    /** A non-blank string that is never a syntactically valid UUID. */
    private static Arbitrary<String> nonUuidString() {
        return Arbitraries.oneOf(
                        Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20),
                        Arbitraries.just("not-a-uuid"),
                        Arbitraries.just("12345"),
                        Arbitraries.just("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"),
                        // a UUID with one character chopped off is still not a valid UUID
                        Arbitraries.create(() -> {
                            String u = UUID.randomUUID().toString();
                            return u.substring(0, u.length() - 1);
                        }))
                .filter(s -> !s.isBlank() && !isUuid(s));
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static Arbitrary<UUID> eventId() {
        return Arbitraries.create(UUID::randomUUID);
    }

    private static Arbitrary<UUID> maybeCorrelation() {
        return ActionArbitraries.maybeNullCorrelationId();
    }
}
