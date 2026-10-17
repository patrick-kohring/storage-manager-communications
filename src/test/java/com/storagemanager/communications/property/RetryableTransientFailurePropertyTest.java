package com.storagemanager.communications.property;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagemanager.communications.action.ActionRetryableException;
import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.action.lead.LeadReadException;
import com.storagemanager.communications.action.support.ActionArbitraries;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.action.support.HarnessFixture;
import com.storagemanager.communications.action.support.SuccessfulActionCase;
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
 * jqwik property test for the retryable-transient-failure behavior of the {@code ActionProcessor}
 * (lead-communication-action spec, Task 7.14).
 *
 * <p>Feature: lead-communication-action, Property 9: Transient failures are surfaced as retryable
 *
 * <p>For any validated action whose single send-seam invocation raises an error, or whose
 * {@code Lead_Read_Port} raises an error while reading lead data, processing surfaces a retryable
 * processing error ({@link ActionRetryableException}) so the originating message remains eligible for
 * redelivery.
 *
 * <p>Two transient-failure families are exercised, matching the two retryable branches in the design
 * (Error Handling, "Retryable error"):
 * <ul>
 *   <li><strong>Send seam raises (Req 7.5):</strong> a fully-valid {@link SuccessfulActionCase} is
 *       installed, then the counting send seam is configured to throw. The action passes every
 *       validation branch and reaches dispatch, so the seam <em>is</em> invoked (and throws);
 *       {@code invocationCount() == 1} is the expected retryable-path count. Processing must surface
 *       an {@link ActionRetryableException}, not a terminal {@link ActionValidationException}.</li>
 *   <li><strong>Lead read raises (Req 8.4):</strong> a structurally-valid action whose
 *       {@code LeadReadPort} throws a {@link LeadReadException} while reading. The read failure
 *       precedes template resolution, personalization, and the single send-seam call site, so the
 *       seam is never reached: {@code invocationCount() == 0}. This THROW case is deliberately
 *       distinct from the missing-lead case (Property 8, {@code Optional.empty() ->}
 *       {@link ActionValidationException}): a port that <em>raises</em> is transient and retryable,
 *       whereas an empty read is a terminal "lead not found" rejection.</li>
 * </ul>
 *
 * <p>In both families the surfaced error is an {@link ActionRetryableException} and is
 * <em>not</em> an {@link ActionValidationException}, so the inbox leaves the message eligible for
 * redelivery (Req 9.2) rather than consuming it as a terminal rejection.
 *
 * <p>Validates: Requirements 7.5, 8.4, 9.2
 */
class RetryableTransientFailurePropertyTest {

    /**
     * (a) Send seam raises (Req 7.5). For any fully-valid action, when the single send-seam
     * invocation throws, processing surfaces an {@link ActionRetryableException} (not a terminal
     * {@link ActionValidationException}); the seam is invoked exactly once (it is reached and
     * throws), leaving the message eligible for redelivery (Req 9.2).
     *
     * <p>Validates: Requirements 7.5, 9.2
     */
    @Property(tries = 100)
    void sendSeamRaises_isSurfacedAsRetryable(
            @ForAll("successfulActions") SuccessfulActionCase testCase) {

        HarnessFixture harness = new HarnessFixture();
        harness.installSuccessfulCase(testCase);
        // The action is fully valid and reaches dispatch; make the single send-seam invocation throw.
        harness.send().failWith(new RuntimeException("delivery provider unavailable"));

        assertThatThrownBy(() -> harness.processor().process(testCase.envelope()))
                .as("a send-seam failure is surfaced as a retryable processing error (Req 7.5)")
                .isInstanceOf(ActionRetryableException.class)
                // Retryable, not terminal: the message remains eligible for redelivery (Req 9.2).
                .isNotInstanceOf(ActionValidationException.class);

        // The seam was reached and invoked once (it threw); this is the retryable-path count (Req 7.5).
        assertThat(harness.send().invocationCount())
                .as("send seam invoked exactly once on the retryable dispatch path (Req 7.5)")
                .isEqualTo(1);
    }

    /**
     * (b) Lead read raises (Req 8.4). For any structurally-valid action whose {@code LeadReadPort}
     * throws while reading, processing surfaces an {@link ActionRetryableException} (not a terminal
     * {@link ActionValidationException}); the send seam is never reached ({@code invocationCount == 0}),
     * leaving the message eligible for redelivery (Req 9.2). Distinguished from the missing-lead case
     * (Property 8): here the port THROWS rather than returning {@code Optional.empty()}.
     *
     * <p>Validates: Requirements 8.4, 9.2
     */
    @Property(tries = 100)
    void leadReadRaises_isSurfacedAsRetryable(@ForAll("leadReadFailureCases") LeadReadFailureCase testCase) {

        HarnessFixture harness = new HarnessFixture();
        // Configure the port to raise while reading this action's lead (Req 8.4). No template or
        // mapping seeding is needed: the read failure precedes template resolution and dispatch.
        if (testCase.failAll()) {
            harness.leads().failAll(new LeadReadException("upstream lead read timeout"));
        } else {
            harness.leads().failFor(testCase.leadId(), "upstream lead read timeout");
        }

        assertThatThrownBy(() -> harness.processor().process(testCase.envelope()))
                .as("a lead-read failure is surfaced as a retryable processing error (Req 8.4)")
                .isInstanceOf(ActionRetryableException.class)
                // Retryable, not terminal: distinguishes a throwing port (8.4) from a missing lead
                // (8.3, Property 8), and keeps the message eligible for redelivery (Req 9.2).
                .isNotInstanceOf(ActionValidationException.class);

        // The send seam is never reached when the lead read raises (Req 9.1 zero-send on the pre-dispatch
        // failure; the retryable read failure precedes the single send call site).
        assertThat(harness.send().invocationCount())
                .as("send seam invoked zero times when the lead read raises (pre-dispatch failure)")
                .isZero();
    }

    @Provide
    Arbitrary<SuccessfulActionCase> successfulActions() {
        return ActionArbitraries.successfulActionCases();
    }

    /**
     * Structurally-valid {@code SendCommunication} cases whose lead read will be configured to throw.
     * Covers both single-recipient paths (LEAD and explicit) and both the per-id and fail-all read
     * failure configurations, so the retryable-read behavior is shown independent of recipient
     * selection and of how the throw is triggered. Every case carries {@code entityKind == LEAD}, a
     * valid UUID {@code leadId}, and a non-blank {@code templateName}, so the only reason to surface
     * an error is the throwing lead read.
     */
    @Provide
    Arbitrary<LeadReadFailureCase> leadReadFailureCases() {
        Arbitrary<UUID> leadIdArb = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> templateNameArb = ActionArbitraries.templateName();
        Arbitrary<UUID> eventIdArb = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> correlationIdArb = ActionArbitraries.maybeNullCorrelationId();
        Arbitrary<Boolean> failAllArb = Arbitraries.of(true, false);

        // A valid single-recipient selection: either a LEAD type or exactly one email/phone literal.
        Arbitrary<String> explicitLiteralArb =
                Arbitraries.oneOf(ActionArbitraries.emailLiteral(), ActionArbitraries.phoneLiteral());
        Arbitrary<Recipient> recipientArb = Arbitraries.oneOf(
                Arbitraries.just(new Recipient(RecipientType.LEAD, null)),
                explicitLiteralArb.map(literal -> new Recipient(null, literal)));

        return Combinators.combine(leadIdArb, templateNameArb, recipientArb, eventIdArb,
                        correlationIdArb, failAllArb)
                .as((leadId, templateName, recipient, eventId, correlationId, failAll) -> {
                    EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                            EntityType.LEAD,
                            leadId.toString(),
                            templateName,
                            recipient.recipientType(),
                            recipient.explicitRecipient(),
                            eventId,
                            correlationId);
                    return new LeadReadFailureCase(envelope, leadId, failAll);
                });
    }

    /** A structurally-valid action plus how its lead read should be made to throw. */
    private record LeadReadFailureCase(EventEnvelope envelope, UUID leadId, boolean failAll) {
    }

    /** A valid single-recipient selection: either a {@code LEAD} type or one explicit literal. */
    private record Recipient(RecipientType recipientType, String explicitRecipient) {
    }
}
