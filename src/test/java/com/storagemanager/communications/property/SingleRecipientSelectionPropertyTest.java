package com.storagemanager.communications.property;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.action.lead.LeadView;
import com.storagemanager.communications.action.support.ActionArbitraries;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.action.support.HarnessFixture;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property test for the single-recipient-selection behavior of the {@code ActionProcessor}
 * (lead-communication-action spec, Task 7.5).
 *
 * <p>Feature: lead-communication-action, Property 5: An action selects exactly one recipient
 *
 * <p>For any action, processing succeeds in selecting a recipient only when exactly one of
 * (a {@code recipientType} of {@code LEAD}) or (a supplied {@code explicitRecipient}) is present:
 * an action supplying <strong>both</strong> is rejected as requiring a single recipient selection
 * (Req 1.9); an action supplying <strong>neither</strong> is rejected as requiring an explicit
 * recipient (Req 5.3); and an action whose supplied {@code explicitRecipient} is neither a
 * syntactically valid email nor a syntactically valid phone number is rejected naming the
 * explicit-recipient value (Req 5.5). In every rejection case the send seam is invoked zero times
 * (Req 9.1, 9.3).
 *
 * <p>Each case is built on an <em>otherwise-valid</em> action — {@code entityKind == LEAD}, a
 * syntactically valid UUID {@code leadId}, and a non-blank {@code templateName} — so the only reason
 * the processor can reject is the recipient-selection rule under test. The both/neither rejections
 * are enforced during validation before the lead read, so no lead needs to be seeded; a resolvable
 * lead is nonetheless registered in the harness so that the recipient-selection rule is provably the
 * sole gate. A positive control confirms that the two valid single-selections ({@code LEAD} alone,
 * explicit alone) do <strong>not</strong> trip the single-recipient rejection.
 *
 * <p>The processor is driven against the shared harness
 * ({@link com.storagemanager.communications.action.support}): fake template / mapping repositories,
 * a configurable {@code LeadReadPort}, and a counting send seam.
 *
 * <p>Validates: Requirements 1.9, 5.3, 5.5, 9.3
 */
class SingleRecipientSelectionPropertyTest {

    /**
     * An action supplying BOTH a {@code LEAD} recipient type and an {@code explicitRecipient} is
     * rejected as requiring a single recipient selection, invoking the send seam zero times.
     *
     * <p>Validates: Requirements 1.9, 9.3
     */
    @Property(tries = 100)
    void bothRecipientSelections_areRejected(
            @ForAll("validLeadIds") UUID leadId,
            @ForAll("templateNames") String templateName,
            @ForAll("anyRecipientLiterals") String explicitRecipient) {

        HarnessFixture harness = seededHarness(leadId, templateName);
        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                RecipientType.LEAD, explicitRecipient,
                UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("both LEAD and explicitRecipient present is rejected (Req 1.9)")
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining("single recipient selection required");

        assertThat(harness.send().invocationCount())
                .as("rejected action invokes the send seam zero times (Req 9.1)")
                .isZero();
    }

    /**
     * An action supplying NEITHER a {@code LEAD} recipient type nor an {@code explicitRecipient} is
     * rejected as requiring an explicit recipient, invoking the send seam zero times.
     *
     * <p>Validates: Requirements 5.3, 9.3
     */
    @Property(tries = 100)
    void neitherRecipientSelection_isRejected(
            @ForAll("validLeadIds") UUID leadId,
            @ForAll("templateNames") String templateName) {

        HarnessFixture harness = seededHarness(leadId, templateName);
        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                null, null,
                UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("neither selection present is rejected as requiring an explicit recipient (Req 5.3)")
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining("explicit recipient required");

        assertThat(harness.send().invocationCount())
                .as("rejected action invokes the send seam zero times (Req 9.1)")
                .isZero();
    }

    /**
     * An action on the explicit path (no {@code recipientType}) whose {@code explicitRecipient} is
     * neither a syntactically valid email nor a syntactically valid phone number is rejected naming
     * the explicit value, invoking the send seam zero times.
     *
     * <p>Validates: Requirements 5.5, 9.3
     */
    @Property(tries = 100)
    void junkExplicitRecipient_isRejectedNamingTheValue(
            @ForAll("validLeadIds") UUID leadId,
            @ForAll("templateNames") String templateName,
            @ForAll("junkLiterals") String junk) {

        HarnessFixture harness = seededHarness(leadId, templateName);
        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                null, junk,
                UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("a non-email, non-phone explicit recipient is rejected naming the value (Req 5.5)")
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining(junk);

        assertThat(harness.send().invocationCount())
                .as("rejected action invokes the send seam zero times (Req 9.1)")
                .isZero();
    }

    /**
     * Positive control: the two valid single-selections — {@code LEAD} alone and a well-formed
     * explicit recipient alone — do NOT trip the single-recipient-selection rejection. The processor
     * may still reject for an unrelated reason that this test deliberately does not rule out (there
     * is no seeded template), so this control asserts only that the rejection, if any, is never one
     * of the single-recipient messages — confirming the selection rule itself admits exactly these
     * two cases (Req 9.3).
     *
     * <p>Validates: Requirements 1.9, 5.3, 9.3
     */
    @Property(tries = 100)
    void validSingleSelections_doNotTripTheSingleRecipientRule(
            @ForAll("validLeadIds") UUID leadId,
            @ForAll("templateNames") String templateName,
            @ForAll("validSingleSelections") RecipientSelection selection) {

        HarnessFixture harness = seededHarness(leadId, templateName);
        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                selection.recipientType(), selection.explicitRecipient(),
                UUID.randomUUID(), UUID.randomUUID());

        try {
            harness.processor().process(envelope);
            // A send may or may not occur depending on downstream seeding; either way the
            // single-recipient rule admitted this selection, which is what this control asserts.
        } catch (ActionValidationException ex) {
            assertThat(ex.getMessage())
                    .as("a valid single selection is never rejected by the single-recipient rule (Req 9.3)")
                    .doesNotContain("single recipient selection required")
                    .doesNotContain("explicit recipient required");
        }
    }

    /**
     * Builds a harness in which everything OTHER than the recipient selection is valid and
     * resolvable, so the recipient-selection rule under test is provably the sole reason the
     * processor can reject:
     * <ul>
     *   <li>the lead resolves to a {@link LeadView} with both an email and a phone contact and an
     *       {@code EMAIL} preferred method (so a valid LEAD selection is not rejected for a missing
     *       contact);</li>
     *   <li>an org-scoped template named {@code templateName} is seeded in the lead's organization
     *       carrying placeholder-free content on both channels (so template resolution and
     *       channel-content selection and personalization all succeed).</li>
     * </ul>
     * For the both/neither cases validation rejects before the lead read regardless; for the junk
     * explicit-recipient case this seeding means the processor reaches the explicit-format check
     * (Req 5.5) and rejects naming the value rather than failing earlier for an unrelated reason.
     */
    private HarnessFixture seededHarness(UUID leadId, String templateName) {
        HarnessFixture harness = new HarnessFixture();
        UUID organizationId = UUID.randomUUID();
        LeadView leadView = new LeadView(
                organizationId,               // organizationId
                null,                         // facilityId (org-scoped template path)
                "lead@example.com",           // email
                "+15551234567",               // phone
                MessageType.EMAIL,            // preferredMethod
                Map.of());                    // properties
        harness.leads().register(leadId, leadView);
        // Placeholder-free content on both channels: resolvable, needs no mappings, never rejected
        // for missing channel content or unresolved placeholders.
        harness.templates().seedOrgTemplate(
                organizationId, templateName, "hello", "hello");
        return harness;
    }

    // ------------------------------------------------------------------------
    // Generators (reuse the shared harness arbitraries where possible)
    // ------------------------------------------------------------------------

    @Provide
    Arbitrary<UUID> validLeadIds() {
        return net.jqwik.api.Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> templateNames() {
        return ActionArbitraries.templateName();
    }

    /** Any well-formed recipient literal (email or phone) for the both-present case. */
    @Provide
    Arbitrary<String> anyRecipientLiterals() {
        return net.jqwik.api.Arbitraries.oneOf(
                ActionArbitraries.emailLiteral(),
                ActionArbitraries.phoneLiteral());
    }

    @Provide
    Arbitrary<String> junkLiterals() {
        return ActionArbitraries.junkLiteral();
    }

    /** The two valid single-selections: LEAD alone, or a well-formed explicit recipient alone. */
    @Provide
    Arbitrary<RecipientSelection> validSingleSelections() {
        Arbitrary<RecipientSelection> leadOnly =
                net.jqwik.api.Arbitraries.just(new RecipientSelection(RecipientType.LEAD, null));
        Arbitrary<RecipientSelection> explicitOnly = net.jqwik.api.Arbitraries.oneOf(
                        ActionArbitraries.emailLiteral(),
                        ActionArbitraries.phoneLiteral())
                .map(literal -> new RecipientSelection(null, literal));
        return net.jqwik.api.Arbitraries.oneOf(leadOnly, explicitOnly);
    }

    /** A single recipient selection: at most one of the two signals is active. */
    record RecipientSelection(RecipientType recipientType, String explicitRecipient) {
    }
}
