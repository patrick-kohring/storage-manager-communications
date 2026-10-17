package com.storagemanager.communications.dto;

import java.time.Instant;
import java.util.UUID;

import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;

/**
 * Response DTO returned from the Entity Property Mapping API endpoints.
 *
 * <p>Contains every field of an {@link EntityPropertyMapping}. The static {@link #from} mapper
 * populates each response field with the value of the identically-named entity field.
 */
public record EntityPropertyMappingResponse(
        UUID id,
        EntityType entityType,
        String jsonProperty,
        String placeholder,
        Instant createdAt,
        Instant updatedAt
) {
    public static EntityPropertyMappingResponse from(EntityPropertyMapping m) {
        return new EntityPropertyMappingResponse(
                m.getId(),
                m.getEntityType(),
                m.getJsonProperty(),
                m.getPlaceholder(),
                m.getCreatedAt(),
                m.getUpdatedAt()
        );
    }
}
