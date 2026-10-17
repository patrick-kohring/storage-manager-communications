package com.storagemanager.communications.action.property;

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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for {@code ActionProcessor} rejection of a selected template that lacks
 * content for the resolved channel, or a LEAD-path lead that lacks a contact for its preferred
 * channel (lead-communication-action spec, Task 7.11).
 *
 * <p>Reuses the shared harness in {@code com.storagemanager.communications.action.support} (see that
 * package's {@code package-info}): {@link HarnessFixture} wires a {@code DefaultActionProcessor} to
 * the in-memory {@code FakeCommunicationTemplateRepository} (seed via
 * {@code seedOrgTemplate}/{@code seedFacilityTemplate}), {@code ConfigurableLeadReadPort}, and the
 * counting {@code CountingSendSeam}. This test adds no new harness; it only seeds the rejection
 * scenarios it needs.
 *
 * <p><b>Ordering note.</b> In {@code DefaultActionProcessor}, recipient resolution (the LEAD-path
 * "lacks a contact for the preferred channel" check, Req 4.7) runs <em>before</em> channel-content
 * selection (the "template lacks content for the channel" check, Req 4.5/4.6). Each generated case
 * is therefore constructed so the rejection under test is the only one reachable: content-missing
 * cases always supply the preferred-channel contact, and contact-missing cases always supply the
 * channel content.
 */
class MissingContentOrContactRejectionPropertyTest {

    /**
     * Feature: lead-communication-action, Property 7: Missing channel content or preferred-channel
     * contact is rejected
     *
     * <p>For any validated action, when the selected template has no content for the resolved channel
     * ({@code EMAIL} with no {@code emailContent}, or {@code SMS} with no {@code smsContent}), or the
     * action is on the LEAD path and the lead has no contact value for its preferred channel,
     * processing rejects the action indicating, respectively, that the template lacks content for the
     * channel or that the lead lacks a contact for the preferred channel; and the send seam is invoked
     * zero times.
     *
     * <p><b>Validates: Requirements 4.5, 4.6, 4.7</b>
     */
    @Property(tries = 100)
    void property7_missingChannelContentOrPreferredChannelContactIsRejected(
            @ForAll("rejectionScenarios") RejectionScenario scenario) {

        HarnessFixture harness = new HarnessFixture();

        UUID organizationId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String templateName = scenario.templateName();
        boolean emailChannel = scenario.channel() == MessageType.EMAIL;

        // ------------------------------------------------------------------
        // Build the lead view. Every case is on the LEAD path (recipientType == LEAD) so the resolved
        // channel is the lead's preferred method; the explicit path has no 4.7 check and is covered by
        // the content-missing cases via this same LEAD path.
        //   - For a CONTACT-MISSING case: the preferred-channel contact is null (triggering Req 4.7).
        //   - For a CONTENT-MISSING case: the preferred-channel contact is present (so 4.7 does NOT
        //     fire and the later content check, Req 4.5/4.6, is the only reachable rejection).
        // ------------------------------------------------------------------
        String leadEmail;
        String leadPhone;
        if (scenario.contactMissing()) {
            // Drop exactly the preferred-channel contact; the other contact is irrelevant.
            leadEmail = emailChannel ? scenario.absentContact() : scenario.email();
            leadPhone = emailChannel ? scenario.phone() : scenario.absentContact();
        } else {
            // Content-missing case: the preferred-channel contact must be present so resolution
            // succeeds and the content check is reached.
            leadEmail = emailChannel ? scenario.email() : null;
            leadPhone = emailChannel ? null : scenario.phone();
        }

        LeadView leadView = new LeadView(
                organizationId, null, leadEmail, leadPhone, scenario.channel(), Map.of());
        harness.leads().register(leadId, leadView);

        // ------------------------------------------------------------------
        // Seed the org-scoped template (lead has no facility, so org scope resolves).
        //   - CONTENT-MISSING case: the resolved channel's content is null/blank (triggering 4.5/4.6);
        //     the OTHER channel carries content so the template is otherwise well-formed.
        //   - CONTACT-MISSING case: BOTH channels carry content, proving the rejection is the missing
        //     contact (4.7) and not missing content.
        // ------------------------------------------------------------------
        String presentContent = "CONTENT::" + UUID.randomUUID();
        String emailContent;
        String smsContent;
        if (scenario.contactMissing()) {
            emailContent = presentContent;
            smsContent = presentContent;
        } else if (emailChannel) {
            emailContent = scenario.absentContent(); // null or blank -> rejected (Req 4.5)
            smsContent = presentContent;
        } else {
            emailContent = presentContent;
            smsContent = scenario.absentContent();   // null or blank -> rejected (Req 4.6)
        }
        harness.templates().seedOrgTemplate(organizationId, templateName, emailContent, smsContent);

        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                RecipientType.LEAD, null, eventId, null);

        String expectedMessage = scenario.contactMissing()
                ? "lead lacks a contact for the preferred channel"
                : "template lacks content for the channel";

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining(expectedMessage);

        // The send seam is invoked zero times for any rejection (Req 4.5/4.6/4.7, zero-send invariant).
        assertThat(harness.send().invocationCount()).isEqualTo(0);
    }

    // ---------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------

    /**
     * Varies the rejection kind (content-missing vs contact-missing) across both channels
     * ({@code EMAIL}/{@code SMS}), and varies the "absent" representation between {@code null} and a
     * blank string so both the {@code == null} and {@code isBlank()} branches of the processor's
     * content/contact checks are exercised. Valid email/phone literals and a template name round out
     * each case so the only defect is the one under test.
     */
    @Provide
    Arbitrary<RejectionScenario> rejectionScenarios() {
        Arbitrary<Boolean> contactMissing = Arbitraries.of(true, false);
        Arbitrary<MessageType> channel = ActionArbitraries.channel();
        Arbitrary<String> absent = Arbitraries.of(null, "", "   ");
        Arbitrary<String> templateName = ActionArbitraries.templateName();
        Arbitrary<String> email = ActionArbitraries.emailLiteral();
        Arbitrary<String> phone = ActionArbitraries.phoneLiteral();

        return Combinators.combine(contactMissing, channel, absent, templateName, email, phone)
                .as(RejectionScenario::new);
    }

    /**
     * One generated rejection scenario.
     *
     * @param contactMissing {@code true} to drop the LEAD preferred-channel contact (Req 4.7);
     *                       {@code false} to drop the resolved channel's template content (Req 4.5/4.6)
     * @param channel        the resolved channel (lead preferred method)
     * @param absent         the representation of "absent" content/contact ({@code null} or blank)
     * @param templateName   the template name the action selects
     * @param email          a valid email literal (used as the present email contact)
     * @param phone          a valid phone literal (used as the present phone contact)
     */
    record RejectionScenario(
            boolean contactMissing,
            MessageType channel,
            String absent,
            String templateName,
            String email,
            String phone) {

        /** The "absent" value to assign to the missing contact (null or blank). */
        String absentContact() {
            return absent;
        }

        /** The "absent" value to assign to the missing content (null or blank). */
        String absentContent() {
            return absent;
        }
    }
}
