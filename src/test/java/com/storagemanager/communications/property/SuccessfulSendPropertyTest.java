package com.storagemanager.communications.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagemanager.communications.action.ActionResult;
import com.storagemanager.communications.action.support.ActionArbitraries;
import com.storagemanager.communications.action.support.CountingSendSeam;
import com.storagemanager.communications.action.support.HarnessFixture;
import com.storagemanager.communications.action.support.SuccessfulActionCase;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.SendCommunicationRequest;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property test for the successful-send behavior of the {@code ActionProcessor}
 * (lead-communication-action spec, Task 7.13).
 *
 * <p>Feature: lead-communication-action, Property 1: A successful action produces exactly one
 * correctly-mapped send
 *
 * <p>For any action that passes all validation (LEAD or explicit path), with a resolvable template
 * carrying content for the resolved channel and placeholders that all resolve, processing the action
 * invokes the send seam <strong>exactly once</strong> with a request whose {@code messageType}
 * equals the resolved channel, whose single channel-matching contact equals the resolved contact
 * value (the other contact {@code null}), whose {@code messageContent} equals the personalized
 * content, whose {@code facilityId} and {@code organizationId} equal the lead's, whose
 * {@code tenantId} equals the {@code Lead_Id}, whose {@code recipientType} equals {@code LEAD} on the
 * lead path and {@code null} on the explicit path, and whose {@code correlationId} equals the derived
 * correlation id; and processing reports the action as successfully processed.
 *
 * <p>The processor is driven against the shared harness
 * ({@link com.storagemanager.communications.action.support}): fake template / mapping repositories,
 * a configurable {@code LeadReadPort}, and a counting/capturing send seam. The fully-valid cases come
 * from {@link ActionArbitraries#successfulActionCases()}, which also pre-computes the expected
 * channel, contact, personalized content, audit recipient type, and derived correlation id for each
 * case, so the assertions compare the processor's mapping against an independently-derived oracle.
 *
 * <p>Validates: Requirements 2.4, 4.3, 4.4, 5.1, 5.2, 5.4, 7.1, 7.2, 7.4
 */
class SuccessfulSendPropertyTest {

    /**
     * For any fully-valid action, the processor invokes the send seam exactly once with the fully
     * and correctly mapped request and reports the action processed.
     *
     * <p>Validates: Requirements 2.4, 4.3, 4.4, 5.1, 5.2, 5.4, 7.1, 7.2, 7.4
     */
    @Property(tries = 100)
    void successfulAction_producesExactlyOneCorrectlyMappedSend(
            @ForAll("successfulActions") SuccessfulActionCase testCase) {

        HarnessFixture harness = new HarnessFixture();
        harness.installSuccessfulCase(testCase);

        ActionResult result = harness.processor().process(testCase.envelope());

        // Processing reports success (Req 7.4).
        assertThat(result).as("successful action returns a processed result").isNotNull();
        assertThat(result.processed()).as("action reported as processed (Req 7.4)").isTrue();

        // Exactly one send-seam invocation (Req 7.1).
        CountingSendSeam send = harness.send();
        assertThat(send.invocationCount())
                .as("send seam invoked exactly once (Req 7.1)")
                .isEqualTo(1);

        SendCommunicationRequest request = send.singleRequest();

        // messageType == resolved channel (Req 4.3/4.4).
        assertThat(request.messageType())
                .as("messageType equals the resolved channel")
                .isEqualTo(testCase.expectedChannel());

        // Single channel-matching contact == resolved contact; the other contact null (Req 4.3, 4.4, 7.2).
        if (testCase.expectedChannel() == MessageType.EMAIL) {
            assertThat(request.emailAddress())
                    .as("EMAIL contact equals the resolved contact value")
                    .isEqualTo(testCase.expectedContact());
            assertThat(request.phoneNumber())
                    .as("phone contact is null on the EMAIL channel")
                    .isNull();
        } else {
            assertThat(request.phoneNumber())
                    .as("SMS contact equals the resolved contact value")
                    .isEqualTo(testCase.expectedContact());
            assertThat(request.emailAddress())
                    .as("email contact is null on the SMS channel")
                    .isNull();
        }

        // messageContent == personalized content (Req 7.2; personalization per Req 6).
        assertThat(request.messageContent())
                .as("messageContent equals the personalized content")
                .isEqualTo(testCase.expectedContent());

        // facilityId / organizationId == lead's (Req 7.2).
        assertThat(request.facilityId())
                .as("facilityId equals the lead's facility")
                .isEqualTo(testCase.leadView().facilityId());
        assertThat(request.organizationId())
                .as("organizationId equals the lead's organization")
                .isEqualTo(testCase.leadView().organizationId());

        // tenantId == leadId (Req 7.2, Decision D4).
        assertThat(request.tenantId())
                .as("tenantId equals the leadId")
                .isEqualTo(testCase.leadId());

        // recipientType == LEAD on lead path, null on explicit path (Req 5.4).
        assertThat(request.recipientType())
                .as("recipientType equals the audit recipient type (LEAD on lead path, null on explicit)")
                .isEqualTo(testCase.expectedRecipientType());

        // correlationId == derived correlation id (Req 2.4).
        assertThat(request.correlationId())
                .as("correlationId equals the derived correlation id")
                .isEqualTo(testCase.expectedCorrelationId());

        // No subject is carried by this spec's action; it maps to null (design mapping table).
        assertThat(request.emailSubject()).as("emailSubject is null (no subject in this spec)").isNull();
        assertThat(request.userId()).as("userId is null (no interactive user)").isNull();
    }

    @Provide
    Arbitrary<SuccessfulActionCase> successfulActions() {
        return ActionArbitraries.successfulActionCases();
    }
}
