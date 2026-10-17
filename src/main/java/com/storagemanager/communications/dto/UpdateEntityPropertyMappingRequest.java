package com.storagemanager.communications.dto;

import com.storagemanager.communications.model.EntityType;

import jakarta.validation.constraints.Pattern;

/**
 * Partial-update (PATCH) request for an entity property mapping.
 *
 * <p>Every field is optional (nullable). A {@code null} field means "leave the stored value
 * unchanged", so {@code jsonProperty} intentionally carries no {@code @NotBlank}. The
 * {@code placeholder} is validated by {@code @Pattern} only when supplied — the pattern passes on
 * {@code null} but a supplied non-kebab-case value is rejected with a {@code 400} naming
 * {@code placeholder}.
 */
public record UpdateEntityPropertyMappingRequest(
        EntityType entityType,
        String jsonProperty,
        @Pattern(
            regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
            message = "must be kebab-case (lowercase alphanumeric segments separated by single hyphens)"
        )
        String placeholder
) {}
