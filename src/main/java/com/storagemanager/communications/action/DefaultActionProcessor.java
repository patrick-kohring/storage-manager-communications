package com.storagemanager.communications.action;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagemanager.communications.action.lead.LeadReadPort;
import com.storagemanager.communications.action.lead.LeadView;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.CommunicationTemplate;
import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;
import com.storagemanager.communications.model.SendCommunicationRequest;
import com.storagemanager.communications.repository.CommunicationTemplateRepository;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;
import com.storagemanager.communications.service.SendService;
import com.storagemanager.communications.service.TemplatePersonalizer;
import com.storagemanager.communications.service.UnresolvedPlaceholderException;
import com.storagemanager.communications.util.ContactFormat;

/**
 * Default {@link ActionProcessor} implementation that interprets a {@code SendCommunication} action,
 * resolves its single recipient and channel, personalizes a template, and invokes the reused
 * {@code communications-api} send seam exactly once.
 *
 * <p><strong>Incremental build-up.</strong> This class is assembled across several sequential
 * sub-tasks. Task 7.1 (this task) establishes the skeleton and the first three responsibilities:
 * <ol>
 *   <li>envelope {@code eventType} guard (Req 2.1),</li>
 *   <li>correlation-id derivation (Req 2.2, 2.4),</li>
 *   <li>payload parse into {@link SendCommunicationAction} (Req 2.3).</li>
 * </ol>
 * The remaining responsibilities are filled in by later sub-tasks at the clearly-marked extension
 * points below:
 * <ul>
 *   <li>7.4  — action validation + single-recipient selection (Req 1, 5.3, 9.3),</li>
 *   <li>7.6  — lead read + template resolution (Req 3, 8.3, 8.4),</li>
 *   <li>7.9  — recipient + channel resolution + channel content selection (Req 4, 5) [done],</li>
 *   <li>7.12 — personalization + single send-seam dispatch (Req 6.3, 7) [done].</li>
 * </ul>
 *
 * <p>The collaborators needed by those later tasks are injected now via the constructor so the
 * extension points can use them without further wiring changes.
 *
 * <p>Owned by the {@code lead-communication-action} spec.
 */
@Service
public class DefaultActionProcessor implements ActionProcessor {

    private final ObjectMapper objectMapper;
    private final LeadReadPort leadReadPort;
    private final CommunicationTemplateRepository templateRepository;
    private final EntityPropertyMappingRepository entityPropertyMappingRepository;
    private final TemplatePersonalizer templatePersonalizer;
    private final SendService sendService;

    public DefaultActionProcessor(ObjectMapper objectMapper,
                                  LeadReadPort leadReadPort,
                                  CommunicationTemplateRepository templateRepository,
                                  EntityPropertyMappingRepository entityPropertyMappingRepository,
                                  TemplatePersonalizer templatePersonalizer,
                                  SendService sendService) {
        this.objectMapper = objectMapper;
        this.leadReadPort = leadReadPort;
        this.templateRepository = templateRepository;
        this.entityPropertyMappingRepository = entityPropertyMappingRepository;
        this.templatePersonalizer = templatePersonalizer;
        this.sendService = sendService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Task 7.1 implements responsibilities 1-3 (guard, correlation id, payload parse) and then
     * hands off to the extension points implemented by later sub-tasks.
     */
    @Override
    public ActionResult process(EventEnvelope envelope) {
        // Responsibility 1: envelope eventType guard (Req 2.1). Only act on a SendCommunication
        // envelope. In step 5 (A2) the inbox routes only matching envelopes; the guard keeps the
        // contract explicit and independently testable.
        if (envelope == null || !SendCommunicationAction.EVENT_TYPE.equals(envelope.eventType())) {
            throw new ActionValidationException(
                    "envelope eventType is not " + SendCommunicationAction.EVENT_TYPE);
        }

        // Responsibility 2: correlation-id derivation (Req 2.2). Use envelope.correlationId() when
        // non-null, else fall back to envelope.eventId(). Carried through to the send (Req 2.4).
        UUID correlationId = deriveCorrelationId(envelope);

        // Responsibility 3: payload parse (Req 2.3). Bind the payload map to SendCommunicationAction;
        // any binding failure is a terminal malformed-payload rejection (send seam invoked zero
        // times).
        SendCommunicationAction action = parsePayload(envelope);

        // Responsibility 4: action validation + single-recipient selection (Req 1, 5.3, 9.3).
        // Every branch here throws an ActionValidationException before any send-seam call site, so a
        // rejected action invokes the send seam zero times (Req 9.1). The valid leadId is parsed to a
        // UUID and held for the later lead-read/dispatch sub-tasks (7.6, 7.12).
        UUID leadId = validateAction(action);

        // ---- Extension points filled in by later sub-tasks ----

        // Responsibility 5: lead read (Req 3.1, 8). Read the lead view through the port. An empty
        // Optional means no lead exists for the id — a terminal "lead not found" validation rejection
        // (Req 8.3), the send seam invoked zero times. Any error raised by the port (a LeadReadException
        // or any other failure) is a transient condition surfaced as a retryable error so the
        // originating message remains eligible for redelivery (Req 8.4).
        LeadView leadView = readLead(leadId);

        // Responsibility 6: template resolution (Req 3). Resolve the named template within the lead's
        // scope, preferring a facility-level match over an org-level match (Req 3.2, 3.3, 3.5). No match
        // in either scope is a terminal "template not found" rejection (Req 3.4).
        CommunicationTemplate template = resolveTemplate(leadView, action.templateName());

        // Responsibility 7: recipient + channel resolution (Req 4.1, 4.2, 5.1, 5.2, 5.5). Produces
        // the single ResolvedRecipient — exactly one channel and one matching contact value — for
        // either the LEAD preferred-method path or the explicit-recipient path.
        ResolvedRecipient resolvedRecipient = resolveRecipient(action, leadView);

        // Responsibility 8: channel content selection (Req 4.3-4.6). Select emailContent for EMAIL
        // or smsContent for SMS; missing/blank content for the resolved channel is a terminal
        // "template lacks content for the channel" rejection (Req 4.5 for EMAIL, 4.6 for SMS).
        String channelContent = selectChannelContent(template, resolvedRecipient.channel());

        // Responsibility 9: personalization (Req 6). Build the (entityType == LEAD) placeholder ->
        // jsonProperty map and substitute every {{placeholder}} in the selected channel content. An
        // unmapped placeholder is a terminal authoring/config defect: the personalizer raises
        // UnresolvedPlaceholderException, which is translated here into an ActionValidationException
        // naming the placeholder (Req 6.3). Because this rejection happens before the single send-seam
        // call site below, a personalization defect invokes the send seam zero times (Req 9.1).
        Map<String, String> placeholderToJsonProperty = leadPlaceholderMappings();
        final String personalizedContent;
        try {
            personalizedContent = templatePersonalizer.personalize(
                    channelContent, placeholderToJsonProperty, leadView.properties());
        } catch (UnresolvedPlaceholderException ex) {
            throw new ActionValidationException(
                    "unresolved placeholder: " + ex.getPlaceholder(), ex);
        }

        // Responsibility 10: dispatch (Req 7). Build exactly one SendCommunicationRequest and invoke
        // the reused send seam exactly once (Req 7.1). Field mapping per Req 7.2 and Decision D4
        // (tenantId = leadId). The contact is placed on the field matching the resolved channel (the
        // other contact field left null); EMAIL has no subject in this spec's action. This is the one
        // and only send-seam call site, reached only on the happy path after every validation and
        // rejection branch above (zero-send invariant, Req 9.1).
        boolean email = resolvedRecipient.channel() == MessageType.EMAIL;
        SendCommunicationRequest sendRequest = new SendCommunicationRequest(
                leadView.facilityId(),                       // facilityId
                resolvedRecipient.channel(),                 // messageType = resolved channel
                personalizedContent,                         // messageContent = personalized content
                email ? null : resolvedRecipient.contactValue(),  // phoneNumber (SMS only)
                email ? resolvedRecipient.contactValue() : null,  // emailAddress (EMAIL only)
                null,                                        // emailSubject (none in this spec)
                resolvedRecipient.auditRecipientType(),      // recipientType = LEAD or null
                leadId,                                      // tenantId = leadId (D4)
                null,                                        // userId
                correlationId,                               // correlationId = derived (Req 2.4)
                leadView.organizationId());                  // organizationId

        // Invoke the send seam exactly once. A raised error is a transient dispatch failure surfaced
        // as a retryable error so the originating message remains eligible for redelivery (Req 7.5).
        try {
            sendService.sendInternal(sendRequest);
        } catch (RuntimeException ex) {
            throw new ActionRetryableException(
                    "send seam failed while dispatching the resolved recipient", ex);
        }

        // Success: the single resolved recipient was dispatched and audited, so the action is
        // reported as successfully processed and the inbox marks the message processed exactly once
        // (Req 7.4).
        return ActionResult.processedResult();
    }

    /**
     * Builds the {@code placeholder -> jsonProperty} map for {@code entityType == LEAD} from the
     * {@link EntityPropertyMappingRepository} rows (Req 6.1, 6.2). This is the authority for which
     * placeholder inner names are defined (an unmapped placeholder is rejected by personalization,
     * Req 6.3) and for the lead {@code jsonProperty} each placeholder resolves to.
     *
     * <p>All LEAD rows are read in a single unpaged query via the existing entity-type finder; the
     * {@code entity-api} feature remains the sole writer of these rows. Duplicate placeholders are
     * prevented upstream by the {@code (entityType, placeholder)} unique constraint, so the map is
     * built directly from {@code placeholder -> jsonProperty}.
     *
     * @return the LEAD placeholder-inner-name to jsonProperty map (never {@code null})
     */
    private Map<String, String> leadPlaceholderMappings() {
        Map<String, String> placeholderToJsonProperty = new LinkedHashMap<>();
        for (EntityPropertyMapping mapping :
                entityPropertyMappingRepository
                        .findByEntityTypeOrderByCreatedAtDesc(EntityType.LEAD, Pageable.unpaged())
                        .getContent()) {
            placeholderToJsonProperty.put(mapping.getPlaceholder(), mapping.getJsonProperty());
        }
        return placeholderToJsonProperty;
    }

    /**
     * Resolves the single {@link ResolvedRecipient} — exactly one channel and one matching contact
     * value — for the action (Responsibility 7; Req 4.1, 4.2, 5.1, 5.2, 5.5).
     *
     * <p>The single-recipient selection was already validated by {@link #validateAction}, so exactly
     * one of the two paths applies:
     * <ul>
     *   <li><strong>LEAD path</strong> ({@code recipientType == LEAD}): the channel is the lead's
     *       {@code preferredMethod()}; the contact value is {@code email()} for {@code EMAIL} or
     *       {@code phone()} for {@code SMS}. If that contact is absent/blank the action is rejected
     *       with "lead lacks a contact for the preferred channel" (Req 4.7). The audit recipient
     *       type is {@code LEAD} so the resulting {@code Sent_Communication} records the lead as the
     *       recipient (Req 5.4).</li>
     *   <li><strong>Explicit path</strong> ({@code recipientType} absent): the literal is classified
     *       using the shared {@link ContactFormat} predicates — an email-shaped literal yields
     *       {@code EMAIL} + that email, a phone-shaped literal yields {@code SMS} + that phone, and a
     *       literal that is neither is rejected naming {@code explicitRecipient} (Req 5.5). The audit
     *       recipient type is {@code null} (no lead recipient role; the send records the explicit
     *       address only).</li>
     * </ul>
     *
     * <p><strong>Channel-check consistency.</strong> The explicit-path classification uses the same
     * {@link ContactFormat} email/phone predicates that {@code SendService} applies when it
     * re-validates the contact inside {@code send}, so a literal this method classifies as
     * {@code EMAIL}/{@code SMS} is never later rejected by the send as malformed.
     *
     * @param action   the validated action (single-recipient selection already enforced)
     * @param leadView the resolved lead view supplying the preferred method and contacts
     * @return the single resolved recipient for the action
     * @throws ActionValidationException if the lead lacks the preferred-channel contact (Req 4.7) or
     *                                   the explicit literal is neither an email nor a phone (Req 5.5)
     */
    private ResolvedRecipient resolveRecipient(SendCommunicationAction action, LeadView leadView) {
        if (action.recipientType() == RecipientType.LEAD) {
            // LEAD path (Req 4.1, 5.1, 5.4): channel is the lead's preferred contact method; the
            // contact value is the lead's email (EMAIL) or phone (SMS).
            MessageType channel = leadView.preferredMethod();
            String contactValue = channel == MessageType.EMAIL ? leadView.email() : leadView.phone();
            if (contactValue == null || contactValue.isBlank()) {
                throw new ActionValidationException(
                        "lead lacks a contact for the preferred channel");
            }
            return new ResolvedRecipient(channel, contactValue, RecipientType.LEAD);
        }

        // Explicit path (Req 4.2, 5.2, 5.5): classify the literal with the shared ContactFormat
        // predicates so the classification agrees with SendService's own format validation.
        String literal = action.explicitRecipient();
        if (ContactFormat.isValidEmail(literal)) {
            return new ResolvedRecipient(MessageType.EMAIL, literal, null);
        }
        if (ContactFormat.isValidPhone(literal)) {
            return new ResolvedRecipient(MessageType.SMS, literal, null);
        }
        throw new ActionValidationException(
                "explicitRecipient is neither a valid email nor a valid phone number: " + literal);
    }

    /**
     * Selects the template content for the resolved channel (Responsibility 8; Req 4.3-4.6):
     * {@code emailContent} for {@code EMAIL}, {@code smsContent} for {@code SMS}. Missing or blank
     * content for the resolved channel is a terminal rejection indicating the template lacks content
     * for the channel (Req 4.5 for {@code EMAIL}, Req 4.6 for {@code SMS}), so the send seam is
     * invoked zero times.
     *
     * @param template the resolved communication template
     * @param channel  the resolved delivery channel
     * @return the non-blank content for the channel
     * @throws ActionValidationException if the template has no content for the channel (Req 4.5/4.6)
     */
    private String selectChannelContent(CommunicationTemplate template, MessageType channel) {
        String content = channel == MessageType.EMAIL
                ? template.getEmailContent()
                : template.getSmsContent();
        if (content == null || content.isBlank()) {
            throw new ActionValidationException("template lacks content for the channel");
        }
        return content;
    }

    /**
     * Reads the {@link LeadView} for the validated lead id through the {@link LeadReadPort}
     * (Responsibility 5; Req 3.1, 8.3, 8.4).
     *
     * <p>Two distinct outcomes are mapped onto the two processing-error classes:
     * <ul>
     *   <li>an empty {@link Optional} means no lead exists for the id — a terminal
     *       {@link ActionValidationException} "lead not found" (Req 8.3), so the send seam is invoked
     *       zero times and the action is not retried;</li>
     *   <li>any error thrown by the port — a {@code LeadReadException} or any other failure — is a
     *       transient read failure surfaced as an {@link ActionRetryableException} (Req 8.4) so the
     *       originating message remains eligible for redelivery.</li>
     * </ul>
     *
     * @param leadId the validated lead id
     * @return the lead view for the id
     * @throws ActionValidationException if no lead exists for the id (Req 8.3)
     * @throws ActionRetryableException  if the port fails to read the lead (Req 8.4)
     */
    private LeadView readLead(UUID leadId) {
        final Optional<LeadView> leadView;
        try {
            leadView = leadReadPort.findLead(leadId);
        } catch (RuntimeException ex) {
            throw new ActionRetryableException("failed to read lead: " + leadId, ex);
        }
        return leadView.orElseThrow(
                () -> new ActionValidationException("lead not found: " + leadId));
    }

    /**
     * Resolves the named {@link CommunicationTemplate} within the lead's scope (Responsibility 6;
     * Req 3.2, 3.3, 3.4, 3.5), preferring a facility-level match over an org-level match.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>if the lead has a facility, try the facility-level template
     *       {@code (organizationId, facilityId, templateName)}; a match is preferred (Req 3.2, 3.5),</li>
     *   <li>otherwise try the org-level template {@code (organizationId, facilityId IS NULL,
     *       templateName)} (Req 3.3),</li>
     *   <li>no match in either scope is a terminal "template not found" rejection (Req 3.4).</li>
     * </ol>
     *
     * @param leadView     the resolved lead view supplying the organization and facility scope
     * @param templateName the validated, non-blank template name to resolve
     * @return the resolved template (facility-level preferred, else org-level)
     * @throws ActionValidationException if no template matches in either scope (Req 3.4)
     */
    private CommunicationTemplate resolveTemplate(LeadView leadView, String templateName) {
        // Facility-first (Req 3.2, 3.5): only attempt the facility-scoped lookup when the lead has a
        // facility, and prefer it over the org-level match when present.
        if (leadView.facilityId() != null) {
            Optional<CommunicationTemplate> facilityMatch =
                    templateRepository.findByOrganizationIdAndFacilityIdAndTemplateName(
                            leadView.organizationId(), leadView.facilityId(), templateName);
            if (facilityMatch.isPresent()) {
                return facilityMatch.get();
            }
        }

        // Org-level fallback (Req 3.3): a template scoped to the organization with a null facility.
        Optional<CommunicationTemplate> orgMatch =
                templateRepository.findByOrganizationIdAndFacilityIdIsNullAndTemplateName(
                        leadView.organizationId(), templateName);
        if (orgMatch.isPresent()) {
            return orgMatch.get();
        }

        // No match in either scope (Req 3.4): terminal rejection, send seam invoked zero times.
        throw new ActionValidationException("template not found: " + templateName);
    }

    /**
     * Derives the correlation id for the action (Req 2.2): the envelope {@code correlationId} when
     * it is non-null, otherwise the envelope {@code eventId}. The result is passed to the send seam
     * so the resulting {@code Sent_Communication} traces to the originating event (Req 2.4).
     *
     * @param envelope the admitted envelope
     * @return the derived correlation id (never {@code null})
     */
    private UUID deriveCorrelationId(EventEnvelope envelope) {
        return envelope.correlationId() != null ? envelope.correlationId() : envelope.eventId();
    }

    /**
     * Binds the envelope's {@code payload} map to a {@link SendCommunicationAction} via Jackson
     * {@code convertValue} (Req 2.3). Any binding failure — for example a value that cannot coerce
     * to the target type, surfaced by Jackson as an {@link IllegalArgumentException} (which wraps
     * {@code InvalidFormatException}) — is wrapped as a terminal malformed-payload
     * {@link ActionValidationException}; the send seam is therefore invoked zero times.
     *
     * @param envelope the admitted {@code SendCommunication} envelope
     * @return the bound {@link SendCommunicationAction}
     * @throws ActionValidationException if the payload cannot be parsed into the action (Req 2.3)
     */
    private SendCommunicationAction parsePayload(EventEnvelope envelope) {
        try {
            return objectMapper.convertValue(envelope.payload(), SendCommunicationAction.class);
        } catch (IllegalArgumentException ex) {
            throw new ActionValidationException("payload is malformed", ex);
        }
    }

    /**
     * Validates a parsed {@link SendCommunicationAction} and enforces the single-recipient selection
     * (Responsibility 4; Req 1.2, 1.7, 1.8, 1.9, 5.3, 9.3). All checks are deterministic rejections:
     * every failing branch throws an {@link ActionValidationException} before any send-seam call
     * site, upholding the zero-send-on-rejection invariant (Req 9.1).
     *
     * <p>Checks, in order:
     * <ol>
     *   <li>required fields present — {@code entityKind}, {@code leadId}, non-blank
     *       {@code templateName} (Req 1.7),</li>
     *   <li>{@code entityKind == LEAD}, else "unsupported entity kind" (Req 1.2),</li>
     *   <li>{@code leadId} is a syntactically valid UUID, else a rejection naming {@code leadId}
     *       (Req 1.8),</li>
     *   <li>single-recipient selection — exactly one of ({@code recipientType == LEAD}) xor
     *       ({@code explicitRecipient} present) (Decision D2; Req 1.9, 5.3, 9.3).</li>
     * </ol>
     *
     * @param action the parsed action (never {@code null})
     * @return the validated {@code leadId} parsed to a {@link UUID}, for use by later sub-tasks
     * @throws ActionValidationException on any deterministic defect in the action (Req 1)
     */
    private UUID validateAction(SendCommunicationAction action) {
        // Required fields present (Req 1.7). Each missing required field is named individually so the
        // rejection identifies the specific offending field.
        if (action.entityKind() == null) {
            throw new ActionValidationException("required field is missing: entityKind");
        }
        if (action.leadId() == null || action.leadId().isBlank()) {
            throw new ActionValidationException("required field is missing: leadId");
        }
        if (action.templateName() == null || action.templateName().isBlank()) {
            throw new ActionValidationException("required field is missing: templateName");
        }

        // entityKind must be LEAD (Req 1.2). Any other supported entity kind is rejected as
        // unsupported. (An unknown enum string would already have failed as a malformed payload at
        // parse time, Req 2.3.)
        if (action.entityKind() != EntityType.LEAD) {
            throw new ActionValidationException(
                    "unsupported entity kind: " + action.entityKind());
        }

        // leadId must be a syntactically valid UUID (Req 1.8). The field is bound as a String
        // precisely so this rejection can name the leadId field rather than failing generically at
        // parse time.
        final UUID leadId;
        try {
            leadId = UUID.fromString(action.leadId());
        } catch (IllegalArgumentException ex) {
            throw new ActionValidationException("invalid UUID for field: leadId", ex);
        }

        // Single-recipient selection (Decision D2; Req 1.9, 5.3, 9.3). Exactly one of the two signals
        // may be active: recipientType == LEAD, xor a present explicitRecipient. A recipientType value
        // other than LEAD (for example OWNER) is not a valid lead selection, so it does not count as a
        // LEAD selection; combined with an absent explicitRecipient it is rejected as "explicit
        // recipient required".
        boolean leadRecipient = action.recipientType() == RecipientType.LEAD;
        boolean explicitRecipient = action.explicitRecipient() != null
                && !action.explicitRecipient().isBlank();

        if (leadRecipient && explicitRecipient) {
            throw new ActionValidationException("single recipient selection required");
        }
        if (!leadRecipient && !explicitRecipient) {
            throw new ActionValidationException("explicit recipient required");
        }

        return leadId;
    }
}
