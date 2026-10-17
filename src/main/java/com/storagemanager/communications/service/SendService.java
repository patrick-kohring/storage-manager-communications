package com.storagemanager.communications.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storagemanager.communications.exception.EntityNotFoundException;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.SendCommunicationRequest;
import com.storagemanager.communications.model.SendStatus;
import com.storagemanager.communications.model.SentCommunication;
import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;
import com.storagemanager.communications.repository.SentCommunicationRepository;
import com.storagemanager.communications.util.ContactFormat;
import com.storagemanager.communications.util.PublicIdGenerator;

/**
 * Application service for the send-and-log (Communications API) feature.
 *
 * <p>Mirrors the {@code CommunicationService} conventions: a single {@code @Service} annotated with
 * class-level {@code @Transactional}, constructor-injecting its collaborators, and resolving the
 * caller's role/organization through the {@code authz} {@link AuthorizationService} bean.
 *
 * <p>This class currently implements only the {@code send(...)} write flow (task 7.1). The read
 * operations ({@code get} / {@code list}) are added in a later task; the class is intentionally
 * left open for those methods.
 */
@Service
@Transactional
public class SendService {

    /** Bounded number of attempts to generate a unique {@code publicId} before failing (→ 500). */
    private static final int PUBLIC_ID_MAX_ATTEMPTS = 5;

    private final SentCommunicationRepository repository;
    private final DeliveryProviderRegistry registry;
    private final AuthorizationService authz;

    public SendService(SentCommunicationRepository repository,
                       DeliveryProviderRegistry registry,
                       AuthorizationService authz) {
        this.repository = repository;
        this.registry = registry;
        this.authz = authz;
    }

    /**
     * Sends a communication over its {@link MessageType} channel and persists exactly one
     * {@link SentCommunication} audit entry inside a single transaction (Requirement 1.2).
     *
     * <p>The flow is: cross-field validation of the recipient contact (Requirements 2.1-2.6, 2.9),
     * organization resolution/confinement per the caller's role (Requirement 7.8), a single
     * dispatch through the channel's {@link DeliveryProvider} (Requirement 1.2), and persistence of
     * one entity with {@code status = DISPATCHED} (Requirement 1.4), the single channel-matching
     * contact (Requirement 1.5), and {@code dateSent} captured once at completion (Requirement 1.6).
     * A persistence failure rolls back the transaction leaving no partial row (Requirement 1.8).
     *
     * @param request the validated send request (field-level bean validation already applied)
     * @return the persisted {@link SentCommunication} entity
     * @throws IllegalArgumentException on a cross-field/format validation failure (mapped to 400)
     */
    public SentCommunication send(SendCommunicationRequest request) {
        // Org resolution / confinement (Req 7.8), mirroring CommunicationService.create exactly.
        // Resolved from the interactive caller's identity; the resolved org is then authoritative
        // for the shared dispatch/persist core.
        UserIdentity identity = authz.getCurrentUserIdentity();
        UserRole role = identity.getRole();
        UUID orgId;
        if (role == UserRole.ADMIN) {
            orgId = request.organizationId();
            if (orgId == null) {
                throw new IllegalArgumentException("organizationId is required for ADMIN callers");
            }
        } else {
            orgId = identity.getOrganizationId();
            if (request.organizationId() != null && !request.organizationId().equals(orgId)) {
                throw new IllegalArgumentException(
                        "organizationId must match the authenticated organization");
            }
        }

        return dispatchAndPersist(request, orgId);
    }

    /**
     * System-actor send entry used by the inbox/action-processing path. Performs the identical
     * dispatch/persist logic as {@link #send(SendCommunicationRequest)} but treats the supplied
     * {@code request.organizationId()} as authoritative, skipping the interactive-user
     * {@code authz} org-confinement branch (there is no interactive caller in the inbox path; the
     * authoritative org comes from the originating lead/envelope scope).
     *
     * <p>This is an additive, public collaborator entry invoked by the {@code ActionProcessor} in
     * the {@code action} package; it shares the single dispatch/persist core with the public
     * {@code send(...)} so there is exactly one real send/persist implementation. It is public
     * (rather than package-visible) solely so the cross-package {@code ActionProcessor} can call it;
     * the public HTTP {@code send(...)} contract is unchanged.
     *
     * @param request the send request; its {@code organizationId} is authoritative and required
     * @return the persisted {@link SentCommunication} entity
     * @throws IllegalArgumentException on a cross-field/format validation failure, or when
     *                                  {@code organizationId} is not supplied
     */
    public SentCommunication sendInternal(SendCommunicationRequest request) {
        UUID orgId = request.organizationId();
        if (orgId == null) {
            throw new IllegalArgumentException("organizationId is required for system-actor sends");
        }
        return dispatchAndPersist(request, orgId);
    }

    /**
     * The single shared dispatch/persist core for both {@link #send(SendCommunicationRequest)} and
     * {@link #sendInternal(SendCommunicationRequest)}. Performs cross-field validation of the
     * recipient contact (Requirements 2.1-2.6, 2.9), a single dispatch through the channel's
     * {@link DeliveryProvider} (Requirement 1.2), and persistence of exactly one
     * {@link SentCommunication} with {@code status = DISPATCHED} (Requirement 1.4), the single
     * channel-matching contact (Requirement 1.5), and {@code dateSent} captured once at completion
     * (Requirement 1.6), inside the single surrounding transaction (Requirements 1.2, 1.8).
     *
     * <p>The {@code orgId} argument is the already-resolved authoritative organization: for the
     * public {@code send(...)} it is the role-confined org, and for {@code sendInternal(...)} it is
     * the system-supplied org.
     */
    private SentCommunication dispatchAndPersist(SendCommunicationRequest request, UUID orgId) {
        // 1. Cross-field validation. "Supplied" means non-null AND non-blank after strip().
        String phone = normalize(request.phoneNumber());
        String email = normalize(request.emailAddress());
        boolean phoneSupplied = phone != null;
        boolean emailSupplied = email != null;

        // Exactly-one-of (Req 2.1, 2.2).
        if (!phoneSupplied && !emailSupplied) {
            throw new IllegalArgumentException("exactly one recipient contact is required");
        }
        if (phoneSupplied && emailSupplied) {
            throw new IllegalArgumentException("only one recipient contact may be supplied");
        }

        MessageType messageType = request.messageType();

        // Channel/contact consistency (Req 2.5, 2.6) and contact format for the channel (Req 2.9).
        String recipientContact;
        if (messageType == MessageType.EMAIL) {
            if (!emailSupplied) {
                throw new IllegalArgumentException(
                        "emailAddress is required when messageType is EMAIL");
            }
            if (!isValidEmail(email)) {
                throw new IllegalArgumentException("emailAddress is not a valid email address");
            }
            recipientContact = email;
        } else { // SMS
            if (!phoneSupplied) {
                throw new IllegalArgumentException(
                        "phoneNumber is required when messageType is SMS");
            }
            if (!isValidPhone(phone)) {
                throw new IllegalArgumentException("phoneNumber is not a valid phone number");
            }
            recipientContact = phone;
        }

        // 2. Dispatch exactly once through the channel's provider (Req 1.2).
        DeliveryResult result = registry.providerFor(messageType).dispatch(
                new DeliveryRequest(
                        messageType,
                        recipientContact,
                        request.messageContent(),
                        request.emailSubject(),
                        request.correlationId()));

        // 3. Build and persist exactly one entity.
        SentCommunication entity = new SentCommunication();
        entity.setPublicId(generateUniquePublicId());
        entity.setFacilityId(request.facilityId());
        entity.setMessageContent(request.messageContent());
        entity.setMessageType(messageType);
        if (messageType == MessageType.EMAIL) {
            entity.setEmailAddress(recipientContact);
        } else {
            entity.setPhoneNumber(recipientContact);
        }
        entity.setStatus(SendStatus.DISPATCHED);
        entity.setRecipientType(request.recipientType());
        entity.setTenantId(request.tenantId());
        entity.setUserId(request.userId());
        entity.setEmailSubject(request.emailSubject());
        entity.setCorrelationId(request.correlationId());
        entity.setOrganizationId(orgId);
        entity.setDeliveryProvider(result.providerId());
        entity.setProviderMessageId(result.providerMessageId());
        entity.setFailureReason(result.failureReason());
        // dateSent captured once at completion, not later than the response instant (Req 1.6).
        entity.setDateSent(Instant.now());

        return repository.save(entity);
    }

    /**
     * Fetch one {@link SentCommunication} by id. Missing → {@link EntityNotFoundException} (404).
     * An ADMIN may read any row; an OWNER may read only a row whose {@code organizationId} equals
     * the JWT org, else {@link AccessDeniedException} (403). Mirrors
     * {@code CommunicationService.get} exactly (missing → 404 first, then org mismatch → 403).
     *
     * @param id the id of the sent communication to fetch
     * @return the persisted {@link SentCommunication} entity
     * @throws EntityNotFoundException if no row matches the id (mapped to 404)
     * @throws AccessDeniedException if an OWNER caller's org does not own the row (mapped to 403)
     */
    @Transactional(readOnly = true)
    public SentCommunication get(UUID id) {
        SentCommunication entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("SentCommunication", id));

        UserIdentity identity = authz.getCurrentUserIdentity();
        if (identity.getRole() != UserRole.ADMIN
                && !entity.getOrganizationId().equals(identity.getOrganizationId())) {
            throw new AccessDeniedException(
                    "Insufficient permissions for sent communication " + id);
        }
        return entity;
    }

    /**
     * List {@link SentCommunication} entries newest-first (date-sent descending, id descending as a
     * deterministic tiebreaker), optionally filtered by facility id and/or message type. An ADMIN
     * sees rows across all organizations and facilities subject only to the supplied filters
     * (Requirements 4.6, 7.6); an OWNER is confined to its own organization (Requirements 4.5, 7.5).
     * The correct repository finder is chosen based on which of {@code facilityId} /
     * {@code messageType} are non-null.
     *
     * @param facilityId  optional facility id filter (null = no facility filter)
     * @param messageType optional message type filter (null = no message type filter)
     * @param pageable    page/size (ordering is baked into the finder method names)
     * @return a page of matching {@link SentCommunication} entries, newest-first
     */
    @Transactional(readOnly = true)
    public Page<SentCommunication> list(UUID facilityId, MessageType messageType, Pageable pageable) {
        UserIdentity identity = authz.getCurrentUserIdentity();

        if (identity.getRole() == UserRole.ADMIN) {
            if (facilityId != null && messageType != null) {
                return repository.findByFacilityIdAndMessageTypeOrderByDateSentDescIdDesc(
                        facilityId, messageType, pageable);
            }
            if (facilityId != null) {
                return repository.findByFacilityIdOrderByDateSentDescIdDesc(facilityId, pageable);
            }
            if (messageType != null) {
                return repository.findByMessageTypeOrderByDateSentDescIdDesc(messageType, pageable);
            }
            return repository.findAllByOrderByDateSentDescIdDesc(pageable);
        }

        UUID orgId = identity.getOrganizationId();
        if (facilityId != null && messageType != null) {
            return repository.findByOrganizationIdAndFacilityIdAndMessageTypeOrderByDateSentDescIdDesc(
                    orgId, facilityId, messageType, pageable);
        }
        if (facilityId != null) {
            return repository.findByOrganizationIdAndFacilityIdOrderByDateSentDescIdDesc(
                    orgId, facilityId, pageable);
        }
        if (messageType != null) {
            return repository.findByOrganizationIdAndMessageTypeOrderByDateSentDescIdDesc(
                    orgId, messageType, pageable);
        }
        return repository.findByOrganizationIdOrderByDateSentDescIdDesc(orgId, pageable);
    }

    /**
     * Normalizes a raw contact value: returns the stripped value when non-null and non-blank after
     * {@code strip()}, otherwise {@code null} ("not supplied").
     */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static boolean isValidEmail(String email) {
        // Delegates to the shared ContactFormat predicates (design "Channel-check consistency") so
        // the send-side format validation agrees exactly with the ActionProcessor's explicit-path
        // channel classification. Behavior is unchanged: ContactFormat mirrors the exact regexes
        // and digit bounds previously inlined here.
        return ContactFormat.isValidEmail(email);
    }

    private static boolean isValidPhone(String phone) {
        return ContactFormat.isValidPhone(phone);
    }

    /**
     * Generates a unique {@code publicId} with a bounded retry loop, mirroring the storage-manager-api
     * {@code FacilityService.generateUniquePublicId} shape: up to {@value #PUBLIC_ID_MAX_ATTEMPTS}
     * attempts, pre-checking {@link SentCommunicationRepository#existsByPublicId(String)}. If every
     * attempt collides a {@link RuntimeException} is thrown, which the {@code GlobalExceptionHandler}
     * maps to HTTP 500 (Requirement 1.8).
     */
    private String generateUniquePublicId() {
        for (int i = 0; i < PUBLIC_ID_MAX_ATTEMPTS; i++) {
            String candidate = PublicIdGenerator.generate();
            if (!repository.existsByPublicId(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Unable to generate a unique publicId after " + PUBLIC_ID_MAX_ATTEMPTS + " attempts");
    }
}
