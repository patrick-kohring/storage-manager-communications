package com.storagemanager.communications.dto;

import com.storagemanager.communications.model.EntityType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for creating a new entity property mapping. All three fields are required.
 *
 * <p>{@code @NotNull} on {@code entityType} yields a {@code 400} naming {@code entityType} when the
 * JSON field is absent or explicitly null. When the JSON supplies a non-enum string (for example
 * {@code "CUSTOMER"}), binding fails at Jackson as an {@code HttpMessageNotReadableException}
 * mapped to {@code 400} before {@code @Valid} runs. {@code @NotBlank} on {@code jsonProperty} and
 * {@code placeholder} yields a {@code 400} naming the blank field, and {@code @Pattern} on
 * {@code placeholder} enforces the kebab-case format.
 */
@Schema(example = """
        {
          "entityType": "LEAD",
          "jsonProperty": "firstName",
          "placeholder": "first-name"
        }""")
public record CreateEntityPropertyMappingRequest(
        @Schema(example = "LEAD")
        @NotNull EntityType entityType,

        @Schema(example = "firstName", maxLength = 255)
        @NotBlank String jsonProperty,

        @Schema(example = "first-name", maxLength = 255)
        @NotBlank
        @Pattern(
            regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
            message = "must be kebab-case (lowercase alphanumeric segments separated by single hyphens)"
        )
        String placeholder
) {}
