package com.storagemanager.communications.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * JPA entity representing a single immutable audit entry of a communication the service dispatched.
 *
 * <p>Each row records what was sent over a {@link MessageType} channel (email or SMS), the single
 * recipient contact value for that channel (exactly one of {@code phoneNumber}/{@code emailAddress}
 * is non-null, enforced by request/service validation rather than a column constraint), the
 * dispatch outcome ({@link SendStatus}, defaulting to {@code DISPATCHED}), and tracing/audit
 * metadata.
 *
 * <p>Mirrors the {@code EntityPropertyMapping}/{@code CommunicationTemplate} style: a
 * {@code GenerationType.UUID} primary key, explicit {@code @Column} names with nullability/length
 * matching the {@code V5} migration one-for-one (so {@code ddl-auto=validate} passes), and
 * {@code Instant} {@code createdAt}/{@code updatedAt} timestamps set by {@code @PrePersist} /
 * {@code @PreUpdate}.
 *
 * <p>The {@code dateSent} value is set once by the service to the instant the send completed; it is
 * independent of {@code createdAt}/{@code updatedAt}, which are maintained solely by the lifecycle
 * callbacks.
 */
@Entity
@Table(name = "sent_communications")
public class SentCommunication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 64)
    private String publicId;

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    @Column(name = "date_sent", nullable = false)
    private Instant dateSent;

    @Column(name = "message_content", nullable = false, length = 10000)
    private String messageContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 16)
    private MessageType messageType;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "email_address", length = 254)
    private String emailAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SendStatus status = SendStatus.DISPATCHED;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", length = 16)
    private RecipientType recipientType;

    @Column(name = "email_subject", length = 255)
    private String emailSubject;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "delivery_provider", length = 64)
    private String deliveryProvider;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }

    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }

    public Instant getDateSent() { return dateSent; }
    public void setDateSent(Instant dateSent) { this.dateSent = dateSent; }

    public String getMessageContent() { return messageContent; }
    public void setMessageContent(String messageContent) { this.messageContent = messageContent; }

    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String emailAddress) { this.emailAddress = emailAddress; }

    public SendStatus getStatus() { return status; }
    public void setStatus(SendStatus status) { this.status = status; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }

    public RecipientType getRecipientType() { return recipientType; }
    public void setRecipientType(RecipientType recipientType) { this.recipientType = recipientType; }

    public String getEmailSubject() { return emailSubject; }
    public void setEmailSubject(String emailSubject) { this.emailSubject = emailSubject; }

    public UUID getCorrelationId() { return correlationId; }
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }

    public String getDeliveryProvider() { return deliveryProvider; }
    public void setDeliveryProvider(String deliveryProvider) { this.deliveryProvider = deliveryProvider; }

    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
