package com.storagemanager.communications.action;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.storagemanager.communications.action.lead.LeadReadPort;
import com.storagemanager.communications.action.lead.LeadView;
import com.storagemanager.communications.action.lead.StubLeadReadPort;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.action.support.HarnessFixture;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;
import com.storagemanager.communications.model.SendCommunicationRequest;

/**
 * Example / edge-case unit tests for the structural binding and routing facts of the
 * {@code lead-communication-action} spec (Task 9.1). These are the single-scenario, non-property
 * facts the design's Testing Strategy routes to example tests rather than to the jqwik properties:
 *
 * <ul>
 *   <li>{@code entityKind} / {@code leadId} / {@code templateName} field binding from the payload
 *       into the processed action (Req 1.1, 1.3, 1.4);</li>
 *   <li>{@code recipientType} LEAD-or-absent binding and its effect on the resolved audit recipient
 *       type (Req 1.5, 1.6);</li>
 *   <li>the {@code eventType == "SendCommunication"} guard (Req 2.1);</li>
 *   <li>the lead's organization / facility obtained via the {@link LeadReadPort} and used for
 *       template resolution and the resulting send (Req 3.1);</li>
 *   <li>the {@link LeadReadPort} interface shape, confirmed through {@link StubLeadReadPort}
 *       (Req 8.1, 8.2).</li>
 * </ul>
 *
 * <p>These reuse the shared harness in {@code com.storagemanager.communications.action.support}
 * ({@link HarnessFixture}, {@link ActionEnvelopes}) — a {@code DefaultActionProcessor} wired to
 * in-memory fakes and a counting send seam. Each binding is observed indirectly through the single
 * captured {@link SendCommunicationRequest}, which is the only externally-visible product of a
 * successfully processed action.
 */
class StructuralBindingAndRoutingExampleTest {

    // ---------------------------------------------------------------------------
    // Field binding: entityKind / leadId / templateName (Req 1.1, 1.3, 1.4)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("entityKind=LEAD, leadId, and templateName bind from the payload and drive one send"
            + " (Req 1.1, 1.3, 1.4)")
    void bindsEntityKindLeadIdAndTemplateName() {
        HarnessFixture harness = new HarnessFixture();

        UUID organizationId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String templateName = "lead-welcome";

        // An org-scoped template named exactly as the payload's templateName, so a correct
        // templateName binding is required for resolution to succeed.
        harness.templates().seedOrgTemplate(
                organizationId, templateName, "Welcome to our facility!", null);
        // LEAD/EMAIL lead so the happy path reaches the single send.
        harness.leads().register(leadId, new LeadView(
                organizationId, null, "lead@example.com", null, MessageType.EMAIL, Map.of()));

        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                RecipientType.LEAD, null, eventId, null);

        ActionResult result = harness.processor().process(envelope);

        assertThat(result.processed()).isTrue();
        assertThat(harness.send().invocationCount()).isEqualTo(1);

        SendCommunicationRequest request = harness.send().singleRequest();
        // leadId binds and is carried onto the send as the tenant reference (Decision D4): proves the
        // payload leadId was parsed and used.
        assertThat(request.tenantId()).isEqualTo(leadId);
        // templateName binds: the resolved template's content was dispatched, which only happens when
        // the bound name matched the seeded template.
        assertThat(request.messageContent()).isEqualTo("Welcome to our facility!");
        // entityKind=LEAD binds and is accepted as the only supported entity kind (no rejection).
        assertThat(request.messageType()).isEqualTo(MessageType.EMAIL);
    }

    @Test
    @DisplayName("a templateName that does not match any template is rejected as not found (Req 1.4)")
    void mismatchedTemplateNameIsRejected() {
        HarnessFixture harness = new HarnessFixture();

        UUID organizationId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();

        harness.templates().seedOrgTemplate(
                organizationId, "the-seeded-name", "body", null);
        harness.leads().register(leadId, new LeadView(
                organizationId, null, "lead@example.com", null, MessageType.EMAIL, Map.of()));

        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), "a-different-name",
                RecipientType.LEAD, null, UUID.randomUUID(), null);

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining("template not found");
        assertThat(harness.send().invocationCount()).isEqualTo(0);
    }

    // ---------------------------------------------------------------------------
    // recipientType LEAD-or-absent binding (Req 1.5, 1.6)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("recipientType=LEAD binds and drives the lead-recipient path (audit type LEAD)"
            + " (Req 1.5)")
    void recipientTypeLeadBindsAndDrivesLeadPath() {
        HarnessFixture harness = new HarnessFixture();

        UUID organizationId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();

        harness.templates().seedOrgTemplate(organizationId, "t", "body", null);
        harness.leads().register(leadId, new LeadView(
                organizationId, null, "lead@example.com", null, MessageType.EMAIL, Map.of()));

        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), "t",
                RecipientType.LEAD, null, UUID.randomUUID(), null);

        harness.processor().process(envelope);

        SendCommunicationRequest request = harness.send().singleRequest();
        // LEAD path: the lead's own captured contact is used and the audit recipient type is LEAD.
        assertThat(request.recipientType()).isEqualTo(RecipientType.LEAD);
        assertThat(request.emailAddress()).isEqualTo("lead@example.com");
    }

    @Test
    @DisplayName("an absent recipientType binds to the explicit-recipient path (audit type null)"
            + " (Req 1.6)")
    void absentRecipientTypeBindsToExplicitPath() {
        HarnessFixture harness = new HarnessFixture();

        UUID organizationId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();

        harness.templates().seedOrgTemplate(organizationId, "t", "body", null);
        // The lead supplies org/facility/properties only; its own contacts are null to prove the
        // explicit literal (not the lead contact) is used on the absent-recipientType path.
        harness.leads().register(leadId, new LeadView(
                organizationId, null, null, null, MessageType.EMAIL, Map.of()));

        // recipientType omitted (null) -> explicit path; explicitRecipient is an email literal.
        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), "t",
                null, "owner@example.com", UUID.randomUUID(), null);

        harness.processor().process(envelope);

        SendCommunicationRequest request = harness.send().singleRequest();
        // Explicit path: the literal is used and there is no lead recipient role (audit type null).
        assertThat(request.recipientType()).isNull();
        assertThat(request.emailAddress()).isEqualTo("owner@example.com");
        assertThat(request.messageType()).isEqualTo(MessageType.EMAIL);
    }

    // ---------------------------------------------------------------------------
    // eventType == "SendCommunication" guard (Req 2.1)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("an envelope whose eventType != \"SendCommunication\" is not processed (Req 2.1)")
    void nonMatchingEventTypeIsNotProcessed() {
        HarnessFixture harness = new HarnessFixture();

        // A payload that would otherwise be a perfectly valid SendCommunication action, but carried
        // in an envelope whose eventType is something else. The guard must reject before any work.
        Map<String, Object> payload = ActionEnvelopes.payloadMap(
                EntityType.LEAD, UUID.randomUUID().toString(), "t",
                RecipientType.LEAD, null);

        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                "SomeOtherEventType",          // eventType != SendCommunicationAction.EVENT_TYPE
                1,
                Instant.now(),
                UUID.randomUUID(),
                null,
                EntityType.LEAD.name(),
                UUID.randomUUID(),
                ActorType.SYSTEM,
                null,
                null,
                null,
                payload);

        // Per the current DefaultActionProcessor, a non-matching eventType is rejected with an
        // ActionValidationException and the send seam is never invoked.
        assertThatThrownBy(() -> harness.processor().process(envelope))
                .isInstanceOf(ActionValidationException.class)
                .hasMessageContaining(SendCommunicationAction.EVENT_TYPE);
        assertThat(harness.send().invocationCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("an envelope whose eventType equals \"SendCommunication\" is processed (Req 2.1)")
    void matchingEventTypeIsProcessed() {
        HarnessFixture harness = new HarnessFixture();

        UUID organizationId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();

        harness.templates().seedOrgTemplate(organizationId, "t", "body", null);
        harness.leads().register(leadId, new LeadView(
                organizationId, null, "lead@example.com", null, MessageType.EMAIL, Map.of()));

        // ActionEnvelopes always sets eventType = SendCommunicationAction.EVENT_TYPE, confirming the
        // guard admits the matching envelope.
        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), "t",
                RecipientType.LEAD, null, UUID.randomUUID(), null);

        assertThat(envelope.eventType()).isEqualTo(SendCommunicationAction.EVENT_TYPE);

        ActionResult result = harness.processor().process(envelope);

        assertThat(result.processed()).isTrue();
        assertThat(harness.send().invocationCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------
    // Lead org/facility obtained via the port and used for routing (Req 3.1)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("the lead's org and facility (from the port) drive facility-scoped template"
            + " resolution and the send's org/facility (Req 3.1)")
    void leadOrgAndFacilityFromPortDriveResolutionAndSend() {
        HarnessFixture harness = new HarnessFixture();

        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        String templateName = "scoped";

        // Two templates share the name: a facility-scoped one (matching the lead's facility) and an
        // org-scoped one. If the processor reads the lead's org+facility via the port, the
        // facility-scoped template is preferred and its content is dispatched.
        harness.templates().seedFacilityTemplate(
                organizationId, facilityId, templateName, "FACILITY-CONTENT", null);
        harness.templates().seedOrgTemplate(
                organizationId, templateName, "ORG-CONTENT", null);

        // The port is the only source of the lead's org and facility.
        harness.leads().register(leadId, new LeadView(
                organizationId, facilityId, "lead@example.com", null, MessageType.EMAIL, Map.of()));

        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), templateName,
                RecipientType.LEAD, null, UUID.randomUUID(), null);

        harness.processor().process(envelope);

        SendCommunicationRequest request = harness.send().singleRequest();
        // Facility-scoped template selected -> the lead's facility (from the port) was used for
        // resolution (Req 3.1, 3.2, 3.5).
        assertThat(request.messageContent()).isEqualTo("FACILITY-CONTENT");
        // The send carries the lead's org and facility, not the envelope-level organizationId.
        assertThat(request.organizationId()).isEqualTo(organizationId);
        assertThat(request.facilityId()).isEqualTo(facilityId);
    }

    // ---------------------------------------------------------------------------
    // LeadReadPort interface shape via StubLeadReadPort (Req 8.1, 8.2)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("LeadReadPort.findLead returns the configured LeadView fields for a known id,"
            + " and empty for an unknown id (Req 8.1, 8.2)")
    void leadReadPortExposesConfiguredLeadViewFields() {
        StubLeadReadPort port = new StubLeadReadPort();

        UUID leadId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        LeadView configured = new LeadView(
                organizationId,
                facilityId,
                "lead@example.com",
                "+15551234567",
                MessageType.SMS,
                Map.of("firstName", "Ada", "balance", 42));

        port.register(leadId, configured);

        // Known id: the port exposes exactly the configured fields (Req 8.1 interface shape).
        Optional<LeadView> found = port.findLead(leadId);
        assertThat(found).isPresent();
        LeadView view = found.get();
        assertThat(view.organizationId()).isEqualTo(organizationId);
        assertThat(view.facilityId()).isEqualTo(facilityId);
        assertThat(view.email()).isEqualTo("lead@example.com");
        assertThat(view.phone()).isEqualTo("+15551234567");
        assertThat(view.preferredMethod()).isEqualTo(MessageType.SMS);
        assertThat(view.properties()).containsEntry("firstName", "Ada")
                .containsEntry("balance", 42);

        // Unknown id: the stub returns empty without calling storage-manager-api (Req 8.2, 8.3).
        assertThat(port.findLead(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("LeadReadPort type can hold the stub implementation (interface shape, Req 8.1)")
    void leadReadPortInterfaceReferencesStub() {
        // The stub satisfies the LeadReadPort contract: a single findLead(UUID) -> Optional<LeadView>.
        LeadReadPort port = new StubLeadReadPort();
        assertThat(port.findLead(UUID.randomUUID())).isEmpty();
    }
}
