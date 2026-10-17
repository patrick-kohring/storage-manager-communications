package com.storagemanager.communications.service;

import java.util.UUID;

import com.storagemanager.communications.model.MessageType;

/**
 * Immutable description of a single delivery to be dispatched through a {@link DeliveryProvider}.
 *
 * <p>Carries the channel, the single channel-matching recipient contact value (an email address
 * when {@code messageType} is {@link MessageType#EMAIL}, a phone number when {@link MessageType#SMS}),
 * the message content, an optional email subject, and the originating correlation id.
 *
 * @param messageType      the delivery channel for this request
 * @param recipientContact the single recipient contact value matching the channel
 * @param messageContent   the message body to deliver
 * @param emailSubject      the email subject line (nullable; relevant only for the EMAIL channel)
 * @param correlationId     the correlation id tracing to the originating event or workflow (nullable)
 */
public record DeliveryRequest(
        MessageType messageType,
        String recipientContact,
        String messageContent,
        String emailSubject,
        UUID correlationId) {
}
