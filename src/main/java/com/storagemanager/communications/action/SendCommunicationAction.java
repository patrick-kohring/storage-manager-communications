package com.storagemanager.communications.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.RecipientType;

/**
 * The {@code SendCommunication} action payload, carried inside {@code EventEnvelope.payload} when
 * {@code eventType == SendCommunicationAction.EVENT_TYPE}. Bound from the envelope payload map by the
 * {@code ActionProcessor} via Jackson. All cross-field validation lives in {@code ActionProcessor} so
 * each rejection can name the specific offending condition (Requirement 1).
 *
 * <p>{@code recipientType} is deliberately the shared communications-api {@link RecipientType}
 * restricted at validation time to {@code LEAD} or {@code null} (Decision D2): an absent value
 * activates the explicit-recipient path.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SendCommunicationAction(
        EntityType entityKind,        // required; must be LEAD (Req 1.1, 1.2)
        String leadId,                // required; parsed to UUID by the processor (Req 1.3, 1.8)
        String templateName,          // required; non-blank (Req 1.4)
        RecipientType recipientType,  // optional; LEAD or null (Req 1.5, D2)
        String explicitRecipient      // optional; one email or phone literal (Req 1.6)
) {

    /**
     * The canonical {@code EventEnvelope.eventType} value that carries a
     * {@link SendCommunicationAction} in its payload (Req 2.1, Decision D1).
     */
    public static final String EVENT_TYPE = "SendCommunication";
}
