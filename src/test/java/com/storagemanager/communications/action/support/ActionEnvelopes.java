package com.storagemanager.communications.action.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.storagemanager.communications.action.SendCommunicationAction;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.RecipientType;

/**
 * Helper for building {@link EventEnvelope}s that carry a {@code SendCommunication} action payload
 * for the {@code ActionProcessor} property-test harness (lead-communication-action spec).
 *
 * <p>Every envelope produced here has {@code eventType == }{@link SendCommunicationAction#EVENT_TYPE}
 * so it passes the processor's eventType guard (Req 2.1). The payload is a {@link Map} of the exact
 * action fields the processor binds via Jackson {@code convertValue} (Req 2.3), so a test can either
 * hand over a ready-made payload map (for the malformed/structural-rejection generators of
 * Properties 6/10) or supply the individual {@code SendCommunicationAction} fields (for the
 * happy-path generators of Property 1).
 *
 * <p>The {@code correlationId} is configurable independently of the {@code eventId} so the
 * correlation-id-derivation property (Req 2.2, Property 11) can exercise both the present and null
 * cases.
 */
public final class ActionEnvelopes {

    private ActionEnvelopes() {
    }

    /**
     * Builds a {@code SendCommunication} envelope carrying the given payload map, with explicit
     * {@code eventId} and {@code correlationId} (the latter may be {@code null} to exercise the
     * {@code correlationId == null -> eventId} fallback of Req 2.2).
     *
     * @param payload       the action payload map placed in {@code EventEnvelope.payload}
     * @param eventId       the envelope {@code eventId}
     * @param correlationId the envelope {@code correlationId}, or {@code null}
     * @return a well-formed {@code SendCommunication} envelope
     */
    public static EventEnvelope envelopeWithPayload(Map<String, Object> payload,
                                                    UUID eventId,
                                                    UUID correlationId) {
        return new EventEnvelope(
                eventId,
                SendCommunicationAction.EVENT_TYPE,
                1,
                Instant.now(),
                UUID.randomUUID(),          // organizationId (envelope-level; processor uses the lead's)
                null,                       // facilityId (nullable)
                EntityType.LEAD.name(),     // entityType (envelope-level routing hint)
                eventId,                    // entityId (any UUID)
                ActorType.SYSTEM,
                null,                       // actorId
                correlationId,
                null,                       // causationId
                payload);
    }

    /**
     * Builds a {@code SendCommunication} envelope from individual action fields. Only non-null
     * fields are placed in the payload map, so an omitted field (for example {@code recipientType}
     * on the explicit path, or a deliberately missing required field) is genuinely absent from the
     * bound payload.
     *
     * @param entityKind        action {@code entityKind} (nullable to omit)
     * @param leadId            action {@code leadId} string (nullable to omit)
     * @param templateName      action {@code templateName} (nullable to omit)
     * @param recipientType     action {@code recipientType} (nullable to omit / explicit path)
     * @param explicitRecipient action {@code explicitRecipient} (nullable to omit)
     * @param eventId           envelope {@code eventId}
     * @param correlationId     envelope {@code correlationId}, or {@code null}
     * @return a {@code SendCommunication} envelope carrying the assembled payload
     */
    public static EventEnvelope envelopeFor(EntityType entityKind,
                                            String leadId,
                                            String templateName,
                                            RecipientType recipientType,
                                            String explicitRecipient,
                                            UUID eventId,
                                            UUID correlationId) {
        return envelopeWithPayload(
                payloadMap(entityKind, leadId, templateName, recipientType, explicitRecipient),
                eventId, correlationId);
    }

    /**
     * Assembles the action payload map from individual fields, omitting any {@code null} field so
     * absence is faithfully represented (a missing required field is truly missing, not bound to
     * {@code null} by a present key).
     */
    public static Map<String, Object> payloadMap(EntityType entityKind,
                                                 String leadId,
                                                 String templateName,
                                                 RecipientType recipientType,
                                                 String explicitRecipient) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (entityKind != null) {
            payload.put("entityKind", entityKind.name());
        }
        if (leadId != null) {
            payload.put("leadId", leadId);
        }
        if (templateName != null) {
            payload.put("templateName", templateName);
        }
        if (recipientType != null) {
            payload.put("recipientType", recipientType.name());
        }
        if (explicitRecipient != null) {
            payload.put("explicitRecipient", explicitRecipient);
        }
        return payload;
    }
}
