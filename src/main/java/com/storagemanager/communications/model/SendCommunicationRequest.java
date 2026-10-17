package com.storagemanager.communications.model;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Jakarta Bean Validation request DTO for the send-and-log endpoint
 * ({@code POST /api/v1/communications}).
 *
 * <p>Only single-field rules are expressed here as field-level annotations: required fields
 * ({@code facilityId}, {@code messageType}, {@code messageContent}), the message-content length
 * bound (Requirement 5.4), and the maximum lengths of the optional contact/subject fields
 * (Requirements 2.7, 2.8, 5.4). An unknown {@code messageType} string fails Jackson binding as a
 * body-readability {@code 400} (Requirement 2.8).
 *
 * <p>The cross-field rules (exactly-one-of phone/email, channel/contact consistency, and
 * contact-format-for-channel) are intentionally <em>not</em> expressed here. They are enforced in
 * {@code SendService} so each error can name the specific offending condition (Requirements
 * 2.1-2.6, 2.9).
 *
 * <p>{@code organizationId} is nullable in the request body; the service confines the persisted
 * organization per the caller's role (Requirement 7.8).
 *
 * <p>Validates: Requirements 2.7, 2.8, 5.4.
 */
public record SendCommunicationRequest(
        @NotNull UUID facilityId,
        @NotNull MessageType messageType,
        @NotBlank @Size(min = 1, max = 10000) String messageContent,
        @Size(max = 32) String phoneNumber,
        @Size(max = 254) String emailAddress,
        @Size(max = 255) String emailSubject,
        RecipientType recipientType,
        UUID tenantId,
        UUID userId,
        UUID correlationId,
        UUID organizationId
) {}
