package com.storagemanager.communications.action;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.storagemanager.communications.action.lead.LeadReadPort;
import com.storagemanager.communications.action.lead.LeadView;
import com.storagemanager.communications.action.lead.StubLeadReadPort;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.CommunicationTemplate;
import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;
import com.storagemanager.communications.model.SendStatus;
import com.storagemanager.communications.model.SentCommunication;
import com.storagemanager.communications.repository.CommunicationTemplateRepository;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;
import com.storagemanager.communications.repository.SentCommunicationRepository;
import com.storagemanager.communications.service.SendService;
import com.storagemanager.communications.service.TemplatePersonalizer;

/**
 * End-to-end integration example feeding a well-formed {@code SendCommunication}
 * {@link EventEnvelope} through the <strong>real</strong> {@link ActionProcessor} against the
 * <strong>real</strong> send seam, asserting exactly one {@link SentCommunication} is persisted
 * with the expected fields (Task 9.3; Design Testing Strategy, end-to-end example).
 *
 * <p>This ties Property 1 (a successful action produces exactly one correctly-mapped send) and the
 * reused {@code communications-api} send seam together at the integration seam, using real Spring
 * wiring rather than hand-assembled fakes:
 * <ul>
 *   <li>the real {@link DefaultActionProcessor} (envelope binding, correlation-id derivation,
 *       payload parse, validation, lead read, template resolution, recipient/channel resolution,
 *       personalization, single dispatch),</li>
 *   <li>the real {@link SendService} + real {@code DeliveryProviderRegistry} stub providers + real
 *       {@link TemplatePersonalizer}, all autowired by Spring,</li>
 *   <li>the {@link StubLeadReadPort} (the only active {@link LeadReadPort} under the test/default
 *       profile; External Assumption A3 for the live adapter), seeded directly with the lead view,
 *       and</li>
 *   <li>the real JPA repositories ({@link CommunicationTemplateRepository},
 *       {@link EntityPropertyMappingRepository}, {@link SentCommunicationRepository}) backed by the
 *       in-memory H2 schema created by Hibernate ({@code ddl-auto=create-drop}).</li>
 * </ul>
 *
 * <p><strong>No security context is required.</strong> The processor dispatches through
 * {@link SendService#sendInternal(com.storagemanager.communications.model.SendCommunicationRequest)},
 * which treats the lead's organization id as authoritative and does <em>not</em> consult
 * {@code authz.getCurrentUserIdentity()} — so this test drives {@code process(...)} directly with no
 * authenticated principal. The bare {@link SpringBootTest} starts the full context (the resource
 * server starts under the test issuer config without a live JWKS fetch), mirroring the lightweight
 * pattern of {@code StubLeadReadPortWiringSmokeTest}.
 *
 * <p>Validates: Requirements 7.1, 7.2, 7.4
 */
@SpringBootTest
class EnvelopeToSendEndToEndIntegrationTest {

    @Autowired
    private ActionProcessor actionProcessor;

    @Autowired
    private LeadReadPort leadReadPort;

    @Autowired
    private CommunicationTemplateRepository templateRepository;

    @Autowired
    private EntityPropertyMappingRepository mappingRepository;

    @Autowired
    private SentCommunicationRepository sentCommunicationRepository;

    private StubLeadReadPort stubLeadReadPort;

    @BeforeEach
    void resetState() {
        // Isolate from any other suite state that shares the singleton stub / H2 schema.
        stubLeadReadPort = (StubLeadReadPort) leadReadPort;
        stubLeadReadPort.clear();
        sentCommunicationRepository.deleteAll();
        templateRepository.deleteAll();
        mappingRepository.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        stubLeadReadPort.clear();
        sentCommunicationRepository.deleteAll();
        templateRepository.deleteAll();
        mappingRepository.deleteAll();
    }

    /**
     * Happy path: a well-formed LEAD-path {@code SendCommunication} envelope, a facility-scoped
     * template with an {@code emailContent} carrying placeholders, the matching
     * {@code (entityType=LEAD)} mappings, and a lead whose preferred method is {@code EMAIL} and
     * whose org/facility match the seeded template. Driving {@code process(...)} must persist
     * exactly one {@link SentCommunication} whose fields map per Requirement 7.2 (channel-matching
     * contact, personalized content, org/facility, tenant = leadId, recipient type, correlation id,
     * status {@code DISPATCHED}).
     */
    @Test
    void wellFormedEnvelope_persistsExactlyOneSentCommunicationWithExpectedFields() {
        UUID leadId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String leadEmail = "dana@example.com";

        // Seed a facility-scoped template whose (org, facility) match the lead, with an email body
        // carrying two distinct placeholders plus a repeated one (personalization consistency).
        CommunicationTemplate template = new CommunicationTemplate();
        template.setOrganizationId(organizationId);
        template.setFacilityId(facilityId);
        template.setTemplateName("lead-welcome");
        template.setEmailContent("Hi {{first-name}}, welcome to {{facility-name}}. "
                + "See you soon, {{first-name}}!");
        template.setSmsContent("ignored for EMAIL channel");
        template.setEntityType(EntityType.LEAD);
        templateRepository.saveAndFlush(template);

        // Seed the (entityType=LEAD) placeholder -> jsonProperty mappings for the placeholders used.
        mappingRepository.saveAndFlush(leadMapping("firstName", "first-name"));
        mappingRepository.saveAndFlush(leadMapping("facilityName", "facility-name"));

        // Seed the lead via the StubLeadReadPort. Properties are keyed by jsonProperty.
        Map<String, Object> leadProperties = new LinkedHashMap<>();
        leadProperties.put("firstName", "Dana");
        leadProperties.put("facilityName", "Downtown Storage");
        stubLeadReadPort.register(leadId, new LeadView(
                organizationId,
                facilityId,
                leadEmail,
                "+15551234567",
                MessageType.EMAIL,
                leadProperties));

        // Build a well-formed SendCommunication envelope carrying a LEAD-path action payload.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityKind", "LEAD");
        payload.put("leadId", leadId.toString());
        payload.put("templateName", "lead-welcome");
        payload.put("recipientType", "LEAD");
        // explicitRecipient omitted (LEAD path).

        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),                        // eventId
                SendCommunicationAction.EVENT_TYPE,       // eventType = "SendCommunication"
                1,                                        // schemaVersion
                Instant.parse("2024-01-01T00:00:00Z"),    // occurredAt
                organizationId,                           // organizationId (envelope scope)
                facilityId,                               // facilityId (envelope scope)
                "Lead",                                   // entityType (envelope metadata)
                leadId,                                   // entityId
                EventEnvelope.ActorType.SYSTEM,           // actorType
                null,                                     // actorId
                correlationId,                            // correlationId (non-null -> used, Req 2.2)
                null,                                     // causationId
                payload);

        // Act: drive the real processor end-to-end.
        ActionResult result = actionProcessor.process(envelope);

        // The action is reported processed (Req 7.4).
        assertThat(result).isNotNull();
        assertThat(result.processed()).isTrue();

        // Exactly one SentCommunication persisted (Req 7.1).
        List<SentCommunication> persisted = sentCommunicationRepository.findAll();
        assertThat(persisted).hasSize(1);

        SentCommunication sent = persisted.get(0);

        // Field mapping (Req 7.2) + stub-dispatch outcome (Req 7.3 via the reused send seam).
        assertThat(sent.getMessageType()).isEqualTo(MessageType.EMAIL);
        assertThat(sent.getEmailAddress()).isEqualTo(leadEmail);
        assertThat(sent.getPhoneNumber()).isNull();
        assertThat(sent.getMessageContent())
                .isEqualTo("Hi Dana, welcome to Downtown Storage. See you soon, Dana!");
        assertThat(sent.getOrganizationId()).isEqualTo(organizationId);
        assertThat(sent.getFacilityId()).isEqualTo(facilityId);
        assertThat(sent.getTenantId()).as("tenantId == leadId (D4)").isEqualTo(leadId);
        assertThat(sent.getRecipientType()).isEqualTo(RecipientType.LEAD);
        assertThat(sent.getCorrelationId()).isEqualTo(correlationId);
        assertThat(sent.getStatus()).isEqualTo(SendStatus.DISPATCHED);
        assertThat(sent.getDateSent()).isNotNull();
        assertThat(sent.getPublicId()).isNotBlank();
        // Stub delivery provider reported success and recorded no failure (Req 7.3).
        assertThat(sent.getDeliveryProvider()).isNotBlank();
        assertThat(sent.getFailureReason()).isNull();
    }

    private static EntityPropertyMapping leadMapping(String jsonProperty, String placeholder) {
        EntityPropertyMapping mapping = new EntityPropertyMapping();
        mapping.setEntityType(EntityType.LEAD);
        mapping.setJsonProperty(jsonProperty);
        mapping.setPlaceholder(placeholder);
        return mapping;
    }
}
