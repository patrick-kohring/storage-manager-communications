package com.storagemanager.communications.action.property;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.action.lead.LeadView;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.action.support.HarnessFixture;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the cross-cutting zero-send-on-rejection invariant of the
 * {@code ActionProcessor} (lead-communication-action spec, Task 7.15).
 *
 * <p>Reuses the shared harness in
 * {@code com.storagemanager.communications.action.support} (see that package's {@code package-info})
 * — {@link HarnessFixture} wires a {@code DefaultActionProcessor} to the in-memory
 * {@code FakeCommunicationTemplateRepository}, {@code FakeEntityPropertyMappingRepository},
 * {@code ConfigurableLeadReadPort}, and the counting {@code CountingSendSeam}. This test adds no new
 * harness; it only assembles, per generated {@link RejectionKind}, a case that the processor rejects
 * for a validation error, and asserts that the send seam was invoked zero times.
 *
 * <p>This is the single unifying invariant: whenever processing throws a validation rejection
 * (an {@link ActionValidationException}) for any rejection defined by Requirements 1, 2, 3, 4, 5, 6,
 * or 8, zero sends occurred for that action.
 */
class ZeroSendOnRejectionPropertyTest {

    /**
     * Feature: lead-communication-action, Property 10: Any validation rejection invokes the send seam
     * zero times
     *
     * <p>For any action that processing rejects for a validation error (any rejection defined by
     * Requirements 1, 2, 3, 4, 5, 6, or 8), the send seam is invoked exactly zero times for that
     * action.
     *
     * <p>A {@link RejectionKind} spans every rejection family: structural/malformed (Req 1, 2),
     * single-recipient violations (Req 1.9, 5.3, 5.5), template-not-found (Req 3.4), missing channel
     * content and missing preferred-channel contact (Req 4.5, 4.6, 4.7), unmapped placeholder
     * (Req 6.3), and lead-not-found (Req 8.3). For each generated kind the harness is seeded (or
     * deliberately not seeded) so the processor rejects with an {@link ActionValidationException}, and
     * the single assertion holds: {@code CountingSendSeam.invocationCount() == 0}.
     *
     * <p><b>Validates: Requirements 9.1</b>
     */
    @Property(tries = 100)
    void property10_anyValidationRejectionInvokesSendZeroTimes(
            @ForAll("rejectionKinds") RejectionKind kind) {

        HarnessFixture harness = new HarnessFixture();
        EventEnvelope envelope = buildRejectableCase(kind, harness);

        // The unifying assertion: processing throws a validation rejection ...
        assertThatThrownBy(() -> harness.processor().process(envelope))
                .isInstanceOf(ActionValidationException.class);

        // ... and the send seam was invoked exactly zero times for that action (Req 9.1).
        assertThat(harness.send().invocationCount()).isEqualTo(0);
    }

    // ---------------------------------------------------------------------------
    // Rejectable-case builder (one branch per rejection family)
    // ---------------------------------------------------------------------------

    /**
     * Builds an envelope the processor rejects for a validation error of the given {@code kind},
     * seeding the harness appropriately (or not) so the rejection fires at the intended branch. All
     * ids and contacts are freshly randomized per invocation so the generator explores a variety of
     * inputs within each family.
     */
    private EventEnvelope buildRejectableCase(RejectionKind kind, HarnessFixture harness) {
        UUID eventId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        String templateName = "tmpl-" + UUID.randomUUID();

        switch (kind) {
            // ---- Structural / malformed (Req 1, 2) ----

            case MISSING_ENTITY_KIND: // Req 1.7
                return ActionEnvelopes.envelopeFor(
                        null, leadId.toString(), templateName, RecipientType.LEAD, null,
                        eventId, null);

            case MISSING_LEAD_ID: // Req 1.7
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, null, templateName, RecipientType.LEAD, null,
                        eventId, null);

            case MISSING_TEMPLATE_NAME: // Req 1.7
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), null, RecipientType.LEAD, null,
                        eventId, null);

            case NON_LEAD_ENTITY_KIND: { // Req 1.2
                // A supported (bindable) entity kind other than LEAD. If no other EntityType value
                // exists in this build, fall back to the malformed-payload case so the generator never
                // produces a non-rejecting envelope.
                EntityType other = firstNonLeadEntityKind();
                if (other == null) {
                    return unparseableEntityKindEnvelope(leadId, templateName, eventId);
                }
                return ActionEnvelopes.envelopeFor(
                        other, leadId.toString(), templateName, RecipientType.LEAD, null,
                        eventId, null);
            }

            case NON_UUID_LEAD_ID: // Req 1.8
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, "not-a-uuid-" + System.nanoTime(), templateName,
                        RecipientType.LEAD, null, eventId, null);

            case UNPARSEABLE_PAYLOAD: // Req 2.3
                return unparseableEntityKindEnvelope(leadId, templateName, eventId);

            // ---- Single-recipient selection (Req 1.9, 5.3, 5.5) ----

            case BOTH_RECIPIENTS: // Req 1.9
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), templateName,
                        RecipientType.LEAD, "owner@example.com", eventId, null);

            case NEITHER_RECIPIENT: // Req 5.3
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), templateName,
                        null, null, eventId, null);

            case JUNK_EXPLICIT_RECIPIENT: { // Req 5.5
                // A valid, resolvable lead + template are seeded so the rejection is specifically the
                // explicit-literal format check (not an earlier branch). The explicit path with a junk
                // literal reaches recipient resolution and is rejected there.
                LeadView leadView = new LeadView(
                        orgId, null, "lead@example.com", null, MessageType.EMAIL, Map.of());
                harness.leads().register(leadId, leadView);
                harness.templates().seedOrgTemplate(orgId, templateName, "Hello", null);
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), templateName,
                        null, "@@@ not a contact", eventId, null);
            }

            // ---- Template not found (Req 3.4) ----

            case TEMPLATE_NOT_FOUND: { // Req 3.4
                // Resolvable lead, but no template seeded in either scope.
                LeadView leadView = new LeadView(
                        orgId, facilityId, "lead@example.com", null, MessageType.EMAIL, Map.of());
                harness.leads().register(leadId, leadView);
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), templateName,
                        RecipientType.LEAD, null, eventId, null);
            }

            // ---- Missing channel content (Req 4.5, 4.6) ----

            case MISSING_EMAIL_CONTENT: { // Req 4.5
                // Lead prefers EMAIL and has an email, but the resolved template has no emailContent.
                LeadView leadView = new LeadView(
                        orgId, null, "lead@example.com", null, MessageType.EMAIL, Map.of());
                harness.leads().register(leadId, leadView);
                harness.templates().seedOrgTemplate(orgId, templateName, null, "sms only");
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), templateName,
                        RecipientType.LEAD, null, eventId, null);
            }

            case MISSING_SMS_CONTENT: { // Req 4.6
                // Lead prefers SMS and has a phone, but the resolved template has no smsContent.
                LeadView leadView = new LeadView(
                        orgId, null, null, "+15551234567", MessageType.SMS, Map.of());
                harness.leads().register(leadId, leadView);
                harness.templates().seedOrgTemplate(orgId, templateName, "email only", null);
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), templateName,
                        RecipientType.LEAD, null, eventId, null);
            }

            // ---- Lead lacks preferred-channel contact (Req 4.7) ----

            case LEAD_MISSING_PREFERRED_CONTACT: { // Req 4.7
                // Lead prefers EMAIL but has no email contact (null). Template content present so the
                // rejection is specifically the missing preferred-channel contact.
                LeadView leadView = new LeadView(
                        orgId, null, null, "+15551234567", MessageType.EMAIL, Map.of());
                harness.leads().register(leadId, leadView);
                harness.templates().seedOrgTemplate(orgId, templateName, "Hello", null);
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), templateName,
                        RecipientType.LEAD, null, eventId, null);
            }

            // ---- Unmapped placeholder (Req 6.3) ----

            case UNMAPPED_PLACEHOLDER: { // Req 6.3
                // Resolvable lead + template whose content references a placeholder that has no LEAD
                // Entity_Property_Mapping seeded, so personalization rejects it.
                LeadView leadView = new LeadView(
                        orgId, null, "lead@example.com", null, MessageType.EMAIL, Map.of());
                harness.leads().register(leadId, leadView);
                harness.templates().seedOrgTemplate(
                        orgId, templateName, "Hi {{first-name}}", null);
                // Deliberately seed no mapping for "first-name".
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), templateName,
                        RecipientType.LEAD, null, eventId, null);
            }

            // ---- Lead not found (Req 8.3) ----

            case LEAD_NOT_FOUND: // Req 8.3
                // No lead registered for the id; the port returns Optional.empty().
                return ActionEnvelopes.envelopeFor(
                        EntityType.LEAD, leadId.toString(), templateName,
                        RecipientType.LEAD, null, eventId, null);

            default:
                throw new IllegalStateException("unhandled rejection kind: " + kind);
        }
    }

    /**
     * Builds an envelope whose payload carries an unparseable {@code entityKind} (a value that is not
     * a valid {@link EntityType} enum name), so Jackson binding fails and the processor rejects it as
     * a malformed payload (Req 2.3).
     */
    private EventEnvelope unparseableEntityKindEnvelope(UUID leadId, String templateName, UUID eventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityKind", "NOT_A_VALID_ENTITY_KIND");
        payload.put("leadId", leadId.toString());
        payload.put("templateName", templateName);
        payload.put("recipientType", "LEAD");
        return ActionEnvelopes.envelopeWithPayload(payload, eventId, null);
    }

    /** @return a non-LEAD {@link EntityType} value, or {@code null} if LEAD is the only member. */
    private static EntityType firstNonLeadEntityKind() {
        for (EntityType type : EntityType.values()) {
            if (type != EntityType.LEAD) {
                return type;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------

    /**
     * Yields every rejection family uniformly so all of Requirements 1, 2, 3, 4, 5, 6, and 8 are
     * exercised across the 100 tries. Each kind maps to a {@link #buildRejectableCase} branch.
     */
    @Provide
    Arbitrary<RejectionKind> rejectionKinds() {
        return Arbitraries.of(RejectionKind.values());
    }

    /**
     * The full set of validation-rejection families spanning Requirements 1, 2, 3, 4, 5, 6, and 8.
     */
    enum RejectionKind {
        // Req 1 / 2 — structural / malformed
        MISSING_ENTITY_KIND,
        MISSING_LEAD_ID,
        MISSING_TEMPLATE_NAME,
        NON_LEAD_ENTITY_KIND,
        NON_UUID_LEAD_ID,
        UNPARSEABLE_PAYLOAD,
        // Req 1.9 / 5.3 / 5.5 — single-recipient selection
        BOTH_RECIPIENTS,
        NEITHER_RECIPIENT,
        JUNK_EXPLICIT_RECIPIENT,
        // Req 3.4 — template not found
        TEMPLATE_NOT_FOUND,
        // Req 4.5 / 4.6 — missing channel content
        MISSING_EMAIL_CONTENT,
        MISSING_SMS_CONTENT,
        // Req 4.7 — lead lacks preferred-channel contact
        LEAD_MISSING_PREFERRED_CONTACT,
        // Req 6.3 — unmapped placeholder
        UNMAPPED_PLACEHOLDER,
        // Req 8.3 — lead not found
        LEAD_NOT_FOUND
    }
}
