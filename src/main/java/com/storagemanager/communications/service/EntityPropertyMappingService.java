package com.storagemanager.communications.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storagemanager.communications.dto.CreateEntityPropertyMappingRequest;
import com.storagemanager.communications.dto.UpdateEntityPropertyMappingRequest;
import com.storagemanager.communications.exception.ConflictException;
import com.storagemanager.communications.exception.EntityNotFoundException;
import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;

/**
 * {@code @Transactional} business logic for Entity Property Mapping CRUD.
 *
 * <p>Unlike {@code CommunicationService}, this feature has <strong>no org/facility scoping</strong>
 * and <strong>no data-dependent authorization</strong>: mappings are global and every endpoint is
 * ADMIN-only, gated declaratively at the controller via {@code @PreAuthorize("@authz.isAdmin()")}.
 * As a result the service injects only its repository and never reads the caller identity.
 *
 * <p>Create/list/get/update/delete methods are added in subsequent tasks. This skeleton establishes
 * the service shape and the service-level {@code (entityType, placeholder)} uniqueness pre-check.
 */
@Service
@Transactional
public class EntityPropertyMappingService {

    private final EntityPropertyMappingRepository repository;

    public EntityPropertyMappingService(EntityPropertyMappingRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a new entity property mapping.
     *
     * <p>Runs the service-level {@code (entityType, placeholder)} uniqueness pre-check first,
     * raising {@link ConflictException} (mapped to {@code 409 Conflict}) on a duplicate. Otherwise
     * builds the entity — copying {@code entityType}, {@code jsonProperty} exactly as supplied (no
     * interpretation, traversal, or validation against any upstream shape), and {@code placeholder}
     * — and persists it. The {@code @PrePersist} callback sets {@code createdAt}/{@code updatedAt}.
     *
     * @param request the validated create request
     * @return the persisted mapping
     * @throws ConflictException if the {@code (entityType, placeholder)} pair already exists
     */
    public EntityPropertyMapping create(CreateEntityPropertyMappingRequest request) {
        requireUniquePair(request.entityType(), request.placeholder());

        EntityPropertyMapping mapping = new EntityPropertyMapping();
        mapping.setEntityType(request.entityType());
        mapping.setJsonProperty(request.jsonProperty());
        mapping.setPlaceholder(request.placeholder());

        return repository.save(mapping);
    }

    /**
     * Lists entity property mappings, newest-first by {@code createdAt}.
     *
     * <p>When {@code entityType} is non-null the result is restricted to mappings of that type;
     * when null, mappings of all types are returned. An empty result yields an empty page.
     *
     * @param entityType optional entity-type filter; {@code null} returns all types
     * @param pageable the pagination/sorting request
     * @return a page of mappings ordered newest-first
     */
    @Transactional(readOnly = true)
    public Page<EntityPropertyMapping> list(EntityType entityType, Pageable pageable) {
        if (entityType != null) {
            return repository.findByEntityTypeOrderByCreatedAtDesc(entityType, pageable);
        }
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * Fetches a single entity property mapping by id.
     *
     * @param id the mapping id
     * @return the mapping
     * @throws EntityNotFoundException if no mapping exists with the given id (mapped to {@code 404})
     */
    @Transactional(readOnly = true)
    public EntityPropertyMapping get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("EntityPropertyMapping", id));
    }

    /**
     * Partially updates an existing entity property mapping.
     *
     * <p>Loads the mapping (raising {@link EntityNotFoundException} → {@code 404} when missing).
     * Computes the prospective {@code (entityType, placeholder)} pair from the request's non-null
     * fields merged over the current values; when that pair differs from the current pair and
     * already exists on a different row, raises {@link ConflictException} → {@code 409}. Each
     * non-null request field is applied; null fields are left unchanged. The {@code @PreUpdate}
     * callback refreshes {@code updatedAt} while {@code createdAt} is left untouched.
     *
     * @param id the mapping id
     * @param request the partial-update request (null fields mean "leave unchanged")
     * @return the updated mapping
     * @throws EntityNotFoundException if no mapping exists with the given id
     * @throws ConflictException if the resulting pair collides with a different existing mapping
     */
    public EntityPropertyMapping update(UUID id, UpdateEntityPropertyMappingRequest request) {
        EntityPropertyMapping mapping = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("EntityPropertyMapping", id));

        EntityType prospectiveType =
                request.entityType() != null ? request.entityType() : mapping.getEntityType();
        String prospectivePlaceholder =
                request.placeholder() != null ? request.placeholder() : mapping.getPlaceholder();

        boolean pairChanged = prospectiveType != mapping.getEntityType()
                || !prospectivePlaceholder.equals(mapping.getPlaceholder());
        if (pairChanged) {
            requireUniquePair(prospectiveType, prospectivePlaceholder);
        }

        if (request.entityType() != null) {
            mapping.setEntityType(request.entityType());
        }
        if (request.jsonProperty() != null) {
            mapping.setJsonProperty(request.jsonProperty());
        }
        if (request.placeholder() != null) {
            mapping.setPlaceholder(request.placeholder());
        }

        return repository.save(mapping);
    }

    /**
     * Deletes an entity property mapping by id.
     *
     * @param id the mapping id
     * @throws EntityNotFoundException if no mapping exists with the given id (mapped to {@code 404})
     */
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new EntityNotFoundException("EntityPropertyMapping", id);
        }
        repository.deleteById(id);
    }

    /**
     * Service-level uniqueness pre-check for the {@code (entityType, placeholder)} pair.
     *
     * <p>Performed before persistence so callers receive a clean {@link ConflictException}
     * (mapped to {@code 409 Conflict}) instead of a raw database constraint violation. The
     * database-level unique constraint remains the backstop against a concurrent-insert race.
     *
     * @throws ConflictException if a mapping with the same {@code (entityType, placeholder)} pair
     *     already exists
     */
    private void requireUniquePair(EntityType entityType, String placeholder) {
        if (repository.existsByEntityTypeAndPlaceholder(entityType, placeholder)) {
            throw new ConflictException(
                    "An entity property mapping for entityType '" + entityType
                            + "' and placeholder '" + placeholder + "' already exists");
        }
    }
}
