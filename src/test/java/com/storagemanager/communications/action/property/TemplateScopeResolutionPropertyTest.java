package com.storagemanager.communications.action.property;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagemanager.communications.action.ActionResult;
import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.action.lead.LeadView;
import com.storagemanager.communications.action.support.ActionArbitraries;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.action.support.HarnessFixture;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for {@code ActionProcessor} template-scope resolution
 * (lead-communication-action spec, Task 7.7).
 *
 * <p>Reuses the shared harness in
 * {@code com.storagemanager.communications.action.support} (see that package's {@code package-info})
 * — {@link HarnessFixture} wires a {@code DefaultActionProcessor} to the in-memory
 * {@code FakeCommunicationTemplateRepository} (with {@code seedFacilityTemplate}/{@code seedOrgTemplate}),
 * {@code ConfigurableLeadReadPort}, and the counting {@code CountingSendSeam}. This test adds no new
 * harness; it only seeds the scope scenarios it needs.
 */
class TemplateScopeResolutionPropertyTest {

    /**
     * Feature: lead-communication-action, Property 4: Template resolution prefers facility scope and
     * rejects when neither scope matches
     *
     * <p>For any lead (with or without a facility) and any template name, template resolution selects
     * the facility-level template whose {@code (organizationId, facilityId)} and {@code templateName}
     * match when one exists; otherwise the org-level template whose {@code (organizationId, null)} and
     * {@code templateName} match when one exists; and when both a facility-level and org-level template
     * match it selects the facility-level one; and when neither matches it rejects the action
     * indicating the template was not found.
     *
     * <p>The chosen template is observed by giving the facility-level and org-level templates distinct,
     * placeholder-free email content and asserting (via the counting send seam's captured
     * {@code messageContent()}) that the selected template's content was dispatched. A single valid
     * LEAD/EMAIL action is used so template resolution is the only variable.
     *
     * <p><b>Validates: Requirements 3.2, 3.3, 3.4, 3.5</b>
     */
    @Property(tries = 100)
    void property4_templateResolutionPrefersFacilityScopeAndRejectsWhenNeitherMatches(
            @ForAll("scopeScenarios") ScopeScenario scenario) {

        HarnessFixture harness = new HarnessFixture();

        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String templateName = scenario.templateName();
        String leadEmail = scenario.leadEmail();

        // Distinct, placeholder-free content for each scope so the dispatched messageContent reveals
        // which template was selected. No placeholders => personalization is the identity function and
        // never interferes with the observation.
        String facilityContent = "FACILITY::" + UUID.randomUUID();
        String orgContent = "ORG::" + UUID.randomUUID();

        // The lead has a facility only when the scenario says so. LEAD/EMAIL path with a present email
        // contact, so the only variable is template resolution.
        UUID leadFacilityId = scenario.leadHasFacility() ? facilityId : null;
        LeadView leadView = new LeadView(
                organizationId, leadFacilityId, leadEmail, null, MessageType.EMAIL, Map.of());
        harness.leads().register(leadId, leadView);

        // Seed templates per scenario. A facility-level template is only meaningful (reachable) when the
        // lead has a facility; the generator guarantees that pairing so "facility present" cases always
        // involve a lead with a facility.
        if (scenario.facilityTemplatePresent()) {
            harness.templates().seedFacilityTemplate(
                    organizationId, facilityId, templateName, facilityContent, null);
        }
        if (scenario.orgTemplatePresent()) {
            harness.templates().seedOrgTemplate(
                    organizationId, templateName, orgContent, null);
        }

        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                RecipientType.LEAD, null, eventId, null);

        boolean facilityMatch = scenario.leadHasFacility() && scenario.facilityTemplatePresent();
        boolean orgMatch = scenario.orgTemplatePresent();

        if (facilityMatch) {
            // A facility-level match exists: it is selected in preference to any org-level match
            // (Req 3.2, 3.5). The dispatched content is the facility template's content.
            ActionResult result = harness.processor().process(envelope);

            assertThat(result.processed()).isTrue();
            assertThat(harness.send().invocationCount()).isEqualTo(1);
            assertThat(harness.send().singleRequest().messageContent()).isEqualTo(facilityContent);
        } else if (orgMatch) {
            // No facility-level match (either the lead has no facility, or no facility template was
            // seeded) but an org-level template matches: it is selected (Req 3.3). This also covers the
            // "lead has no facility, org match present" case.
            ActionResult result = harness.processor().process(envelope);

            assertThat(result.processed()).isTrue();
            assertThat(harness.send().invocationCount()).isEqualTo(1);
            assertThat(harness.send().singleRequest().messageContent()).isEqualTo(orgContent);
        } else {
            // Neither scope matches: the action is rejected indicating the template was not found and
            // the send seam is invoked zero times (Req 3.4).
            assertThatThrownBy(() -> harness.processor().process(envelope))
                    .isInstanceOf(ActionValidationException.class)
                    .hasMessageContaining("template not found");
            assertThat(harness.send().invocationCount()).isEqualTo(0);
        }
    }

    // ---------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------

    /**
     * Varies (lead has a facility?) x (facility template present?) x (org template present?) across
     * the whole truth table, so every resolution branch is exercised: facility-only match, org-only
     * match, both-present (facility preferred), lead-without-facility + org match, and no-match. A
     * facility template is only seeded when the lead has a facility (a facility template with no lead
     * facility is unreachable and would conflate the "no facility match" cases), so that pairing is
     * constrained in the generator.
     */
    @Provide
    Arbitrary<ScopeScenario> scopeScenarios() {
        Arbitrary<Boolean> leadHasFacility = Arbitraries.of(true, false);
        Arbitrary<Boolean> facilityTemplatePresent = Arbitraries.of(true, false);
        Arbitrary<Boolean> orgTemplatePresent = Arbitraries.of(true, false);
        Arbitrary<String> templateName = ActionArbitraries.templateName();
        Arbitrary<String> leadEmail = ActionArbitraries.emailLiteral();

        return Combinators.combine(
                        leadHasFacility, facilityTemplatePresent, orgTemplatePresent,
                        templateName, leadEmail)
                .as((hasFacility, facilityPresent, orgPresent, name, email) ->
                        new ScopeScenario(
                                hasFacility,
                                // A facility template is only reachable when the lead has a facility.
                                hasFacility && facilityPresent,
                                orgPresent,
                                name,
                                email));
    }

    /**
     * One generated template-scope scenario.
     *
     * @param leadHasFacility          whether the lead carries a (non-null) facility id
     * @param facilityTemplatePresent  whether a facility-scoped template is seeded (implies the lead
     *                                 has a facility)
     * @param orgTemplatePresent       whether an org-scoped template is seeded
     * @param templateName             the name the action selects (same name used for both scopes)
     * @param leadEmail                the lead's email contact for the LEAD/EMAIL path
     */
    record ScopeScenario(
            boolean leadHasFacility,
            boolean facilityTemplatePresent,
            boolean orgTemplatePresent,
            String templateName,
            String leadEmail) {
    }
}
