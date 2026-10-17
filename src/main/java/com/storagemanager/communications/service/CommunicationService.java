package com.storagemanager.communications.service;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storagemanager.communications.exception.ConflictException;
import com.storagemanager.communications.exception.EntityNotFoundException;
import com.storagemanager.communications.model.CommunicationTemplate;
import com.storagemanager.communications.model.CreateCommunicationTemplateRequest;
import com.storagemanager.communications.model.UpdateCommunicationTemplateRequest;
import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;
import com.storagemanager.communications.repository.CommunicationTemplateRepository;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;

@Service
@Transactional
public class CommunicationService {

    /** Matches each {@code {{...}}} occurrence; group 1 is the (untrimmed) inner content. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.*?)\\}\\}", Pattern.DOTALL);

    /** A well-formed placeholder inner name: lowercase-alphanumeric kebab-case. */
    private static final Pattern INNER_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final CommunicationTemplateRepository templateRepository;
    private final EntityPropertyMappingRepository mappingRepository;
    private final AuthorizationService authz;

    public CommunicationService(CommunicationTemplateRepository templateRepository,
                                EntityPropertyMappingRepository mappingRepository,
                                AuthorizationService authz) {
        this.templateRepository = templateRepository;
        this.mappingRepository = mappingRepository;
        this.authz = authz;
    }

    public CommunicationTemplate create(CreateCommunicationTemplateRequest request) {
        UserIdentity identity = authz.getCurrentUserIdentity();
        UserRole role = identity.getRole();

        // Org resolution per role. ADMIN supplies the org explicitly; OWNER derives it from the
        // JWT and may not assert a different org.
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

        // Facility-level templates are scoped to a facility within the resolved org. The
        // communications service does not own the facility table, so facility existence /
        // facility-in-org validation is not performed here; the facility_id is recorded as the
        // template's scope. A non-admin caller must at minimum be acting within their own org,
        // which is guaranteed above by deriving orgId from the token.
        UUID facilityId = request.facilityId();
        if (facilityId != null && role != UserRole.ADMIN && !authz.canAccessFacility(facilityId)) {
            throw new AccessDeniedException("Insufficient permissions for facility " + facilityId);
        }

        requireUniqueName(orgId, facilityId, request.templateName());

        // Validate embedded {{...}} placeholders against the effective content/entityType
        // (Requirement 11): an unknown enum string has already failed Jackson binding (400) before
        // reaching the service.
        validatePlaceholders(request.emailContent(), request.smsContent(), request.entityType());

        CommunicationTemplate template = new CommunicationTemplate();
        template.setOrganizationId(orgId);
        template.setFacilityId(facilityId);
        template.setTemplateName(request.templateName());
        template.setEmailContent(request.emailContent());
        template.setSmsContent(request.smsContent());
        template.setEntityType(request.entityType());
        return templateRepository.save(template);
    }

    /**
     * List templates scoped to a single organization, newest-first. For an OWNER the effective
     * org is the JWT org and the supplied param is ignored; for an ADMIN the param is required.
     */
    @Transactional(readOnly = true)
    public Page<CommunicationTemplate> list(UUID organizationIdParam, Pageable pageable) {
        UserIdentity identity = authz.getCurrentUserIdentity();
        UUID effectiveOrgId;
        if (identity.getRole() == UserRole.ADMIN) {
            if (organizationIdParam == null) {
                throw new IllegalArgumentException(
                        "organizationId request parameter is required for ADMIN callers");
            }
            effectiveOrgId = organizationIdParam;
        } else {
            effectiveOrgId = identity.getOrganizationId();
        }
        return templateRepository.findByOrganizationIdOrderByCreatedAtDesc(effectiveOrgId, pageable);
    }

    /**
     * Fetch one template by id. Missing → {@link EntityNotFoundException} (404). An ADMIN may
     * read any template; an OWNER may read only a template whose {@code organizationId} equals
     * the JWT org, else {@link AccessDeniedException} (403).
     */
    @Transactional(readOnly = true)
    public CommunicationTemplate get(UUID id) {
        CommunicationTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("CommunicationTemplate", id));

        UserIdentity identity = authz.getCurrentUserIdentity();
        if (identity.getRole() != UserRole.ADMIN
                && !template.getOrganizationId().equals(identity.getOrganizationId())) {
            throw new AccessDeniedException(
                    "Insufficient permissions for communication template " + id);
        }
        return template;
    }

    /**
     * Partially update a template. Missing → {@link EntityNotFoundException} (404). For an OWNER,
     * the template's org must equal the JWT org, else {@link AccessDeniedException} (403). A
     * rename to a name already used in the template's existing {@code (organizationId,
     * facilityId)} scope → {@link ConflictException} (409). Only non-null request fields are
     * applied. The {@code @PreUpdate} callback refreshes {@code updatedAt} on save.
     */
    public CommunicationTemplate update(UUID id, UpdateCommunicationTemplateRequest request) {
        CommunicationTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("CommunicationTemplate", id));

        UserIdentity identity = authz.getCurrentUserIdentity();
        if (identity.getRole() != UserRole.ADMIN) {
            if (!template.getOrganizationId().equals(identity.getOrganizationId())) {
                throw new AccessDeniedException(
                        "Insufficient permissions for communication template " + id);
            }
            if (template.getFacilityId() != null
                    && !authz.canAccessFacility(template.getFacilityId())) {
                throw new AccessDeniedException(
                        "Insufficient permissions for facility " + template.getFacilityId());
            }
        }

        if (request.templateName() != null
                && !request.templateName().equals(template.getTemplateName())) {
            requireUniqueName(
                    template.getOrganizationId(), template.getFacilityId(), request.templateName());
        }

        if (request.templateName() != null) {
            template.setTemplateName(request.templateName());
        }
        if (request.emailContent() != null) {
            template.setEmailContent(request.emailContent());
        }
        if (request.smsContent() != null) {
            template.setSmsContent(request.smsContent());
        }
        // A non-null entityType updates the association; a null entityType leaves it unchanged
        // (clearing to null is out of scope this iteration — consistent with partial-update
        // semantics for the other fields).
        if (request.entityType() != null) {
            template.setEntityType(request.entityType());
        }

        // Validate placeholders against the template's EFFECTIVE (post-apply) content and
        // entityType (Requirement 11).
        validatePlaceholders(
                template.getEmailContent(), template.getSmsContent(), template.getEntityType());

        return templateRepository.save(template);
    }

    /**
     * Validates the {@code {{...}}} placeholders embedded in the effective (post-apply) template
     * content against the effective {@code entityType} (Communication Template API, Requirement 11).
     *
     * <p>Every {@code {{...}}} occurrence is extracted from both content fields (a null field
     * contributes none) and its inner content is trimmed of leading/trailing ASCII whitespace to
     * yield the inner name. When {@code entityType} is null, any occurrence at all is rejected
     * (placeholders are not permitted without an entity type). When {@code entityType} is non-null,
     * every inner name must be a well-formed kebab placeholder and must exist as an
     * {@code EntityPropertyMapping} placeholder for that entity type, validated against the global
     * mapping set fetched once; zero occurrences pass trivially. Failures raise
     * {@link IllegalArgumentException} (mapped to {@code 400}) naming each distinct offender.
     */
    private void validatePlaceholders(String emailContent, String smsContent,
                                      com.storagemanager.communications.model.EntityType entityType) {
        java.util.List<String> innerNames = new java.util.ArrayList<>();
        extractInto(emailContent, innerNames);
        extractInto(smsContent, innerNames);

        if (entityType == null) {
            if (!innerNames.isEmpty()) {
                throw new IllegalArgumentException(
                        "Placeholders are not permitted when the template has no entityType");
            }
            return;
        }

        java.util.Set<String> invalidFormat = new java.util.LinkedHashSet<>();
        for (String name : innerNames) {
            if (name.isEmpty() || !INNER_NAME.matcher(name).matches()) {
                invalidFormat.add(name);
            }
        }
        if (!invalidFormat.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid placeholder(s): " + String.join(", ", invalidFormat));
        }

        java.util.Set<String> valid =
                new java.util.HashSet<>(mappingRepository.findPlaceholderByEntityType(entityType));
        java.util.Set<String> unknown = new java.util.LinkedHashSet<>();
        for (String name : innerNames) {
            if (!valid.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown placeholder(s) for entityType " + entityType + ": "
                            + String.join(", ", unknown));
        }
    }

    /** Extract each {@code {{...}}} inner name (trimmed) from {@code content} into {@code out}. */
    private static void extractInto(String content, java.util.List<String> out) {
        if (content == null) {
            return;
        }
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(content);
        while (matcher.find()) {
            out.add(matcher.group(1).strip());
        }
    }

    private void requireUniqueName(UUID organizationId, UUID facilityId, String templateName) {
        boolean exists = (facilityId == null)
                ? templateRepository.existsByOrganizationIdAndFacilityIdIsNullAndTemplateName(
                        organizationId, templateName)
                : templateRepository.existsByOrganizationIdAndFacilityIdAndTemplateName(
                        organizationId, facilityId, templateName);
        if (exists) {
            throw new ConflictException(
                    "A communication template named '" + templateName
                            + "' already exists in this scope");
        }
    }
}
