package com.storagemanager.communications.action;

import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;

/**
 * The single concrete send target produced by recipient resolution for a
 * {@link SendCommunicationAction}: exactly one channel and exactly one matching contact value
 * (Requirement 5; design "Recipient + channel resolution").
 *
 * <p>Each action targets exactly one end user and produces exactly one send, so a
 * {@code ResolvedRecipient} always carries a single {@code channel}/{@code contactValue} pair.
 *
 * @param channel            the resolved delivery channel ({@code EMAIL} or {@code SMS}, Req 4)
 * @param contactValue       the single matching contact value: an email address for {@code EMAIL}
 *                           or a phone number for {@code SMS} (Req 4.3, 4.4, 5)
 * @param auditRecipientType the recipient role recorded on the resulting {@code Sent_Communication}:
 *                           {@code LEAD} for the lead path, {@code null} for the explicit-recipient
 *                           path (Req 5.4). Nullable.
 */
public record ResolvedRecipient(
        MessageType channel,
        String contactValue,
        RecipientType auditRecipientType
) {
}
