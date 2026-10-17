package com.storagemanager.communications.property;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagemanager.communications.action.ActionRetryableException;
import com.storagemanager.communications.action.ActionValidationException;
import com.storagemanager.communications.action.support.ActionArbitraries;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.action.support.HarnessFixture;
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
 * jqwik property test for the missing-lead rejection behavior of the {@code ActionProcessor}
 * (lead-communication-action spec, Task 7.8).
 *
 * <p>Feature: lead-communication-action, Property 8: A missing lead is rejected
 *
 * <p>For any action whose {@code Lead_Read_Port} lookup reports that no lead exists for the
 * {@code Lead_Id} ({@code findLead} returns {@link java.util.Optional#empty()}), processing rejects
 * the action indicating the lead was not found.
 *
 * <p>Each generated action is otherwise <strong>fully structurally valid</strong>:
 * {@code entityKind == LEAD}, a syntactically valid UUID {@code leadId}, a non-blank
 * {@code templateName}, and a valid single-recipient selection (exactly one of a {@code LEAD}
 * recipient type or a single email/phone explicit recipient). The lead is simply never registered in
 * the harness's {@link com.storagemanager.communications.action.support.ConfigurableLeadReadPort}, so
 * the port returns {@link java.util.Optional#empty()}. Because the action is otherwise valid, the
 * only reason it can be rejected is the missing lead, so the rejection is attributable to Req 8.3.
 *
 * <p>The rejection must be a terminal {@link ActionValidationException} (the message is never
 * retried), distinguishing it from the port-raises-an-error case (Req 8.4, Task 7.14), which surfaces
 * an {@link ActionRetryableException}. The send seam must be invoked zero times (Req 9.1): the
 * missing-lead check precedes template resolution, personalization, and the single send-seam call
 * site, so no seeding of templates or mappings is needed for this property.
 *
 * <p>Validates: Requirements 8.3
 */
class MissingLeadRejectionPropertyTest {

    /**
     * For any structurally-valid action whose lead is not registered in the port (so {@code findLead}
     * returns {@code Optional.empty()}), processing rejects the action as "lead not found" with a
     * terminal {@link ActionValidationException} and invokes the send seam zero times.
     *
     * <p>Validates: Requirements 8.3
     */
    @Property(tries = 100)
    void missingLead_isRejectedAsLeadNotFound(@ForAll("missingLeadEnvelopes") EventEnvelope envelope) {

        HarnessFixture harness = new HarnessFixture();
        // Deliberately register no lead: the ConfigurableLeadReadPort returns Optional.empty() for the
        // action's leadId, driving the missing-lead branch (Req 8.3).

        assertThatThrownBy(() -> harness.processor().process(envelope))
                .as("a missing lead is rejected with a terminal validation error (Req 8.3)")
                .isInstanceOf(ActionValidationException.class)
                // Terminal, not retryable: distinguishes the missing-lead case (8.3) from a port that
                // raises an error (8.4), which would surface an ActionRetryableException.
                .isNotInstanceOf(ActionRetryableException.class)
                .hasMessageContaining("lead not found");

        // The send seam is invoked zero times for a rejected action (Req 9.1).
        assertThat(harness.send().invocationCount())
                .as("send seam invoked zero times when the lead is missing (Req 9.1)")
                .isZero();
    }

    /**
     * Structurally-valid {@code SendCommunication} envelopes for leads that are never registered in
     * the port. Covers both single-recipient paths so the missing-lead rejection is shown to be
     * independent of recipient selection:
     * <ul>
     *   <li>LEAD path: {@code recipientType == LEAD}, no explicit recipient;</li>
     *   <li>explicit path: {@code recipientType} absent, a single valid email-or-phone literal.</li>
     * </ul>
     * Every envelope carries {@code entityKind == LEAD}, a valid UUID {@code leadId}, and a non-blank
     * {@code templateName}, so the only possible rejection reason is the missing lead.
     */
    @Provide
    Arbitrary<EventEnvelope> missingLeadEnvelopes() {
        Arbitrary<UUID> leadIdArb = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> templateNameArb = ActionArbitraries.templateName();
        Arbitrary<UUID> eventIdArb = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> correlationIdArb = ActionArbitraries.maybeNullCorrelationId();

        // Recipient selection: either the LEAD path (recipientType == LEAD, no explicit recipient) or
        // the explicit path (recipientType absent, one valid email/phone literal). Both are valid
        // single-recipient selections, so neither contributes a rejection reason of its own.
        Arbitrary<String> explicitLiteralArb =
                Arbitraries.oneOf(ActionArbitraries.emailLiteral(), ActionArbitraries.phoneLiteral());
        Arbitrary<Recipient> recipientArb = Arbitraries.oneOf(
                Arbitraries.just(new Recipient(RecipientType.LEAD, null)),
                explicitLiteralArb.map(literal -> new Recipient(null, literal)));

        return Combinators.combine(leadIdArb, templateNameArb, recipientArb, eventIdArb, correlationIdArb)
                .as((leadId, templateName, recipient, eventId, correlationId) ->
                        ActionEnvelopes.envelopeFor(
                                EntityType.LEAD,
                                leadId.toString(),
                                templateName,
                                recipient.recipientType(),
                                recipient.explicitRecipient(),
                                eventId,
                                correlationId));
    }

    /** A valid single-recipient selection: either a {@code LEAD} type or one explicit literal. */
    private record Recipient(RecipientType recipientType, String explicitRecipient) {
    }
}
