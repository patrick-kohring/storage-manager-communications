package com.storagemanager.communications.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;

/**
 * Spring Data JPA repository for {@link EntityPropertyMapping}.
 *
 * <p>Provides the paginated newest-first finders backing the list endpoint (with and without the
 * optional {@code entityType} filter) and the existence-by-pair query backing the service-level
 * {@code (entityType, placeholder)} uniqueness pre-check.
 */
public interface EntityPropertyMappingRepository extends JpaRepository<EntityPropertyMapping, UUID> {

    /**
     * Service-level uniqueness pre-check for the {@code (entityType, placeholder)} pair.
     */
    boolean existsByEntityTypeAndPlaceholder(EntityType entityType, String placeholder);

    /**
     * Unfiltered list, ordered newest-first by {@code createdAt}.
     */
    Page<EntityPropertyMapping> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * {@code entityType}-filtered list, ordered newest-first by {@code createdAt}.
     */
    Page<EntityPropertyMapping> findByEntityTypeOrderByCreatedAtDesc(EntityType entityType, Pageable pageable);

    /**
     * Returns the global set of valid placeholder inner names defined for an {@code entityType},
     * as a single-query projection. Used by {@code CommunicationService} to validate the
     * placeholders embedded in template content against the mappings for the template's effective
     * entity type (Communication Template API, Requirement 11). Read-only from the communications
     * feature's perspective; the entity-api feature remains the sole writer of mappings.
     */
    java.util.List<String> findPlaceholderByEntityType(EntityType entityType);
}
