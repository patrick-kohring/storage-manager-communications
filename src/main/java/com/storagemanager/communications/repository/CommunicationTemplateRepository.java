package com.storagemanager.communications.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.storagemanager.communications.model.CommunicationTemplate;

public interface CommunicationTemplateRepository extends JpaRepository<CommunicationTemplate, UUID> {

    Page<CommunicationTemplate> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    boolean existsByOrganizationIdAndFacilityIdAndTemplateName(UUID organizationId, UUID facilityId, String templateName);

    boolean existsByOrganizationIdAndFacilityIdIsNullAndTemplateName(UUID organizationId, String templateName);

    // Read-only scope finders for lead-communication-action template resolution (Requirements 3.2, 3.3).

    Optional<CommunicationTemplate> findByOrganizationIdAndFacilityIdAndTemplateName(
            UUID organizationId, UUID facilityId, String templateName);

    Optional<CommunicationTemplate> findByOrganizationIdAndFacilityIdIsNullAndTemplateName(
            UUID organizationId, String templateName);
}
