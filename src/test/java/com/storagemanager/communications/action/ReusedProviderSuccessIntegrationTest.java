package com.storagemanager.communications.action;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.storagemanager.communications.action.lead.LeadView;
import com.storagemanager.communications.action.lead.StubLeadReadPort;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.CommunicationTemplate;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;
import com.storagemanager.communications.model.SendStatus;
import com.storagemanager.communications.model.SentCommunication;
import com.storagemanager.communications.repository.CommunicationTemplateRepository;
import com.storagemanager.communications.repository.SentCommunicationRepository;

/**
 * Integration test for Task 9.2: confirms that the reused {@code communications-api} stub
 * {@code Delivery_Provider} reports a <em>successful</em> dispatch and performs <em>no external
 * third-party transmission</em> when a {@code SendCommunication} action is driven end-to-end
 * through the real {@link ActionProcessor} (Requirement 7.3).
 *
 * <p><strong>What this is.</strong> A thin wiring check. The send/no-transmission behavior is owned
 * by the {@code communications-api} spec (the no-op {@code StubEmailDeliveryProvider}/SMS stub, the
 * {@link SendStatus#DISPATCHED} outcome, and the locally-generated {@code providerMessageId} with no
 * network I/O). This test does not re-test that provider; it asserts that routing a lead
 * communication through the real {@code ActionProcessor} + real {@code SendService} + real
 * {@code DeliveryProviderRegistry} (holding the real stub providers) + real
 * {@code TemplatePersonalizer} lands on the reused stub and observes its success outcome.
 *
 * <p><strong>Wiring.</strong> Uses a plain {@link SpringBootTest} so the <em>real</em> stub provider
 * is used — the same lightweight pattern as {@code StubLeadReadPortWiringSmokeTest} and
 * {@code StorageManagerCommunicationsApplicationTests}. The only test seam is the
 * {@link StubLeadReadPort} (the only {@code LeadReadPort} implementation in this spec, A3), which is
 * seeded with a lead so recipient/channel resolution can complete. The system-actor
 * {@code SendService.sendInternal(...)} path is exercised, so no interactive security context is
 * needed.
 *
 * <p><strong>No-external-transmission assertion.</strong> The stub is no-op by construction, so the
 * absence of outbound transmission is observed through its success result as persisted on the
 * {@link SentCommunication}: the status is {@code DISPATCHED}, the delivery provider is the reused
 * stub's {@code stub-email} id, a stub-generated {@code providerMessageId} is present, and there is
 * no {@code failureReason} — exactly how {@code communications-api} represents "success, nothing
 * transmitted externally".
 *
 * <p>This test has a distinct class name from the sibling end-to-end test (Task 9.3) and stays
 * focused on the provider-success / no-transmission wiring aspect.
 *
 * <p>Validates: Requirement 7.3
 */
@SpringBootTest
@DisplayName("Reused communications-api stub provider success through the ActionProcessor (Req 7.3)")
class ReusedProviderSuccessIntegrationTest {

    @Autowired
    private ActionProcessor actionProcessor;

    @Autowired
    private StubLeadReadPort stubLeadReadPort;

    @Autowired
    private CommunicationTemplateRepository templateRepository;

    @Autowired
    private SentCommunicationRepository sentCommunicationRepository;

    @BeforeEach
    void resetState() {
        // Isolate from any lead data seeded by sibling @SpringBootTest classes sharing the context,
        // and start from an empty audit table so the single persisted send is unambiguous.
        stubLeadReadPort.clear();
        sentCommunicationRepository.deleteAll();
    }

    @Test
    @DisplayName("an EMAIL lead send reports success via the reused stub and transmits nothing")
    void reusedStubProviderReportsSuccessAndTransmitsNothing() {
        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String templateName = "lead-welcome";

        // Real org-level template (facility_id null), read by the real CommunicationTemplateRepository
        // during resolution. The lead carries a facility, but no facility-level template with this
        // name exists, so org-level resolution applies (Req 3.3).
        CommunicationTemplate template = new CommunicationTemplate();
        template.setOrganizationId(organizationId);
        template.setFacilityId(null); // org-level template
        template.setTemplateName(templateName);
        template.setEmailContent("Welcome to our facility!");
        template.setEntityType(EntityType.LEAD);
        templateRepository.save(template);

        // Seed the only test seam: the lead supplied through the StubLeadReadPort. The lead belongs
        // to a facility (sent_communications.facility_id is NOT NULL on the reused audit entity).
        stubLeadReadPort.register(leadId, new LeadView(
                organizationId,
                facilityId,
                "lead@example.com",
                null,
                MessageType.EMAIL,          // preferred method -> EMAIL channel
                Map.of()));                 // placeholder-free content needs no properties

        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                RecipientType.LEAD, null,
                UUID.randomUUID(), correlationId);

        // Drive the action end-to-end through the REAL processor + send seam + stub provider.
        ActionResult result = actionProcessor.process(envelope);

        // The action is reported as successfully processed (Req 7.4 ties into the success outcome).
        assertThat(result.processed()).isTrue();

        // Exactly one audit row was persisted by the reused send seam.
        List<SentCommunication> persisted = sentCommunicationRepository.findAll();
        assertThat(persisted).hasSize(1);
        SentCommunication sent = persisted.get(0);

        // Success outcome as represented by communications-api: DISPATCHED + stub provider identity,
        // a stub-generated provider message id, and NO failure reason.
        assertThat(sent.getStatus()).isEqualTo(SendStatus.DISPATCHED);
        assertThat(sent.getDeliveryProvider()).isEqualTo("stub-email");
        assertThat(sent.getProviderMessageId())
                .as("the reused stub sets a locally-generated provider message id (no network call)")
                .isNotBlank()
                .startsWith("stub-email-");
        assertThat(sent.getFailureReason())
                .as("a successful stub dispatch records no failure reason")
                .isNull();

        // Routing/field wiring sanity: the resolved EMAIL channel, contact, content, scope, and
        // trace landed on the persisted send (keeps the check honest without re-testing the provider).
        assertThat(sent.getMessageType()).isEqualTo(MessageType.EMAIL);
        assertThat(sent.getEmailAddress()).isEqualTo("lead@example.com");
        assertThat(sent.getPhoneNumber()).isNull();
        assertThat(sent.getMessageContent()).isEqualTo("Welcome to our facility!");
        assertThat(sent.getOrganizationId()).isEqualTo(organizationId);
        assertThat(sent.getFacilityId()).isEqualTo(facilityId);
        assertThat(sent.getTenantId()).isEqualTo(leadId);
        assertThat(sent.getRecipientType()).isEqualTo(RecipientType.LEAD);
        assertThat(sent.getCorrelationId()).isEqualTo(correlationId);
        // dateSent captured once at completion (part of the success outcome).
        assertThat(sent.getDateSent()).isNotNull();
    }
}
