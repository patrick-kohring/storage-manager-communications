package com.storagemanager.communications.dto;

import java.time.Instant;
import java.util.UUID;

import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;
import com.storagemanager.communications.model.SendStatus;
import com.storagemanager.communications.model.SentCommunication;

/**
 * Response DTO returned from the Communications API endpoints (send, get-by-id, and list).
 *
 * <p>Contains the system-generated {@code id} and {@code publicId} plus every persisted audit field
 * of a {@link SentCommunication}. The static {@link #from} mapper populates each response field with
 * the value of the identically-named entity field, mirroring
 * {@code EntityPropertyMappingResponse.from} / {@code CommunicationTemplateResponse.from}.
 */
public record SentCommunicationResponse(
        UUID id,
        String publicId,
        UUID facilityId,
        Instant dateSent,
        String messageContent,
        MessageType messageType,
        String phoneNumber,
        String emailAddress,
        SendStatus status,
        UUID tenantId,
        UUID userId,
        UUID organizationId,
        RecipientType recipientType,
        String emailSubject,
        UUID correlationId,
        String deliveryProvider,
        String providerMessageId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static SentCommunicationResponse from(SentCommunication s) {
        return new SentCommunicationResponse(
                s.getId(),
                s.getPublicId(),
                s.getFacilityId(),
                s.getDateSent(),
                s.getMessageContent(),
                s.getMessageType(),
                s.getPhoneNumber(),
                s.getEmailAddress(),
                s.getStatus(),
                s.getTenantId(),
                s.getUserId(),
                s.getOrganizationId(),
                s.getRecipientType(),
                s.getEmailSubject(),
                s.getCorrelationId(),
                s.getDeliveryProvider(),
                s.getProviderMessageId(),
                s.getFailureReason(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
