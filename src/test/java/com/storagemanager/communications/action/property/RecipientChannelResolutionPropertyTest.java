package com.storagemanager.communications.action.property;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagemanager.communications.action.ActionResult;
import com.storagemanager.communications.action.lead.LeadView;
import com.storagemanager.communications.action.support.ActionArbitraries;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.action.support.HarnessFixture;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;
import com.storagemanager.communications.model.SendCommunicationRequest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for {@code ActionProcessor} recipient and channel resolution
 * (lead-communication-action spec, Task 7.10).
 *
 * <p>Reuses the shared harness in
 * {@code com.storagemanager.communications.action.support} (see that package's {@code package-info})
 * — {@link HarnessFixture} wires a {@code DefaultActionProcessor} to the in-memory
 * {@code FakeCommunicationTemplateRepository} (with {@code seedOrgTemplate}),
 * {@code ConfigurableLeadReadPort}, and the counting {@code CountingSendSeam}. This test adds no new
 * harness; it only seeds the resolution scenarios it needs.
 */
class RecipientChannelResolutionPropertyTest {

    /**
     * Feature: lead-communication-action, Property 3: Recipient and channel resolution yields exactly
     * one target
     *
     * <p>For any validated action, recipient resolution yields exactly one {@code Resolved_Recipient}:
     * on the LEAD path the channel equals the lead's {@code Preferred_Contact_Method} and the contact
     * is the lead's email for {@code EMAIL} or phone for {@code SMS}; on the explicit path the channel
     * is {@code EMAIL} when the explicit literal is a syntactically valid email and {@code SMS} when it
     * is a syntactically valid phone number, with the contact equal to that literal.
     *
     * <p>The resolved channel and contact are observed through the single captured
     * {@code SendCommunicationRequest}: the resolved channel is {@code messageType()}, and the resolved
     * contact is {@code emailAddress()} for the {@code EMAIL} channel (with {@code phoneNumber()} null)
     * or {@code phoneNumber()} for the {@code SMS} channel (with {@code emailAddress()} null). A valid
     * org-scoped, placeholder-free template carrying content for the resolved channel is seeded so the
     * pipeline reaches the single send and resolution is the only variable.
     *
     * <p><b>Validates: Requirements 4.1, 4.2, 5.1, 5.2</b>
     */
    @Property(tries = 100)
    void property3_recipientAndChannelResolutionYieldsExactlyOneTarget(
            @ForAll("resolutionScenarios") ResolutionScenario scenario) {

        HarnessFixture harness = new HarnessFixture();

        UUID organizationId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String templateName = scenario.templateName();

        // Placeholder-free content on the resolved channel (and the other channel left null): the
        // template carries content exactly for the resolved channel, personalization is the identity
        // function, and the send is reached so resolution can be observed.
        boolean emailChannel = scenario.expectedChannel() == MessageType.EMAIL;
        String content = "BODY::" + UUID.randomUUID();
        String emailContent = emailChannel ? content : null;
        String smsContent = emailChannel ? null : content;

        // The lead view. On the LEAD path the resolved channel's contact must be present (and is the
        // expected resolved contact); on the explicit path the lead contacts are deliberately null so
        // the assertion proves the explicit literal — not a lead contact — is what gets resolved.
        LeadView leadView = new LeadView(
                organizationId,
                null,                              // org-scoped template path; no facility needed
                scenario.leadEmail(),
                scenario.leadPhone(),
                scenario.preferredMethod(),        // drives the LEAD-path channel
                Map.of());
        harness.leads().register(leadId, leadView);

        harness.templates().seedOrgTemplate(organizationId, templateName, emailContent, smsContent);

        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                scenario.recipientType(), scenario.explicitRecipient(), eventId, null);

        ActionResult result = harness.processor().process(envelope);

        // Exactly one resolved target => exactly one send.
        assertThat(result.processed()).isTrue();
        assertThat(harness.send().invocationCount()).isEqualTo(1);

        SendCommunicationRequest request = harness.send().singleRequest();

        // The resolved channel.
        assertThat(request.messageType()).isEqualTo(scenario.expectedChannel());

        // The single matching contact on the resolved channel, with the other contact null.
        if (emailChannel) {
            assertThat(request.emailAddress()).isEqualTo(scenario.expectedContact());
            assertThat(request.phoneNumber()).isNull();
        } else {
            assertThat(request.phoneNumber()).isEqualTo(scenario.expectedContact());
            assertThat(request.emailAddress()).isNull();
        }
    }

    // ---------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------

    /**
     * Builds the four resolution cases named in the design's Testing Strategy for Property 3:
     * <ul>
     *   <li>LEAD path, {@code preferredMethod == EMAIL}, lead has an email
     *       → channel {@code EMAIL}, contact = lead email;</li>
     *   <li>LEAD path, {@code preferredMethod == SMS}, lead has a phone
     *       → channel {@code SMS}, contact = lead phone;</li>
     *   <li>explicit path, email-shaped literal → channel {@code EMAIL}, contact = literal;</li>
     *   <li>explicit path, phone-shaped literal → channel {@code SMS}, contact = literal.</li>
     * </ul>
     * Each case draws fresh email/phone/template-name values from {@link ActionArbitraries} so the
     * resolution holds across the whole contact input space.
     */
    @Provide
    Arbitrary<ResolutionScenario> resolutionScenarios() {
        Arbitrary<String> email = ActionArbitraries.emailLiteral();
        Arbitrary<String> phone = ActionArbitraries.phoneLiteral();
        Arbitrary<String> templateName = ActionArbitraries.templateName();

        // (case selector in [0,3]) x email x phone x templateName
        Arbitrary<Integer> caseSelector = Arbitraries.integers().between(0, 3);

        return Combinators.combine(caseSelector, email, phone, templateName)
                .as((selector, e, p, name) -> switch (selector) {
                    case 0 -> ResolutionScenario.leadEmail(e, name);
                    case 1 -> ResolutionScenario.leadSms(p, name);
                    case 2 -> ResolutionScenario.explicitEmail(e, name);
                    default -> ResolutionScenario.explicitSms(p, name);
                });
    }

    /**
     * One generated recipient/channel resolution scenario, pre-computing the expected resolved
     * channel and contact so the test asserts against an oracle independent of the processor's logic.
     *
     * @param recipientType     the action {@code recipientType} ({@code LEAD} or {@code null})
     * @param explicitRecipient the action {@code explicitRecipient} (explicit path only, else null)
     * @param preferredMethod   the lead's preferred contact method (drives the LEAD-path channel)
     * @param leadEmail         the lead's email contact (LEAD/EMAIL path), else null
     * @param leadPhone         the lead's phone contact (LEAD/SMS path), else null
     * @param templateName      the template name the action selects
     * @param expectedChannel   the channel the processor should resolve
     * @param expectedContact   the single contact value the processor should resolve
     */
    record ResolutionScenario(
            RecipientType recipientType,
            String explicitRecipient,
            MessageType preferredMethod,
            String leadEmail,
            String leadPhone,
            String templateName,
            MessageType expectedChannel,
            String expectedContact) {

        static ResolutionScenario leadEmail(String email, String templateName) {
            return new ResolutionScenario(
                    RecipientType.LEAD, null, MessageType.EMAIL,
                    email, null, templateName, MessageType.EMAIL, email);
        }

        static ResolutionScenario leadSms(String phone, String templateName) {
            return new ResolutionScenario(
                    RecipientType.LEAD, null, MessageType.SMS,
                    null, phone, templateName, MessageType.SMS, phone);
        }

        static ResolutionScenario explicitEmail(String email, String templateName) {
            return new ResolutionScenario(
                    null, email, MessageType.EMAIL,
                    null, null, templateName, MessageType.EMAIL, email);
        }

        static ResolutionScenario explicitSms(String phone, String templateName) {
            return new ResolutionScenario(
                    null, phone, MessageType.SMS,
                    null, null, templateName, MessageType.SMS, phone);
        }
    }
}
