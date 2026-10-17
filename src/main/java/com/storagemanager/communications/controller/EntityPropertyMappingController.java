package com.storagemanager.communications.controller;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storagemanager.communications.dto.CreateEntityPropertyMappingRequest;
import com.storagemanager.communications.dto.EntityPropertyMappingResponse;
import com.storagemanager.communications.dto.UpdateEntityPropertyMappingRequest;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.PagedResponse;
import com.storagemanager.communications.service.EntityPropertyMappingService;

import jakarta.validation.Valid;

/**
 * REST controller exposing the Entity Property Mapping CRUD endpoints under
 * {@code /api/v1/entity-property-mappings}.
 *
 * <p>Every endpoint carries a flat ADMIN-only {@code @PreAuthorize("@authz.isAdmin()")} gate so the
 * role check runs before any business logic — a non-ADMIN caller is rejected with {@code 403}
 * regardless of whether the referenced id exists (Requirements 13.1, 13.5). Authentication itself
 * is enforced by {@code SecurityConfig} via {@code anyRequest().authenticated()} (no {@code permitAll}
 * is added for these paths), so a request without a verified JWT yields {@code 401}.
 */
@RestController
public class EntityPropertyMappingController {

    private final EntityPropertyMappingService service;

    public EntityPropertyMappingController(EntityPropertyMappingService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/entity-property-mappings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.isAdmin()")
    public EntityPropertyMappingResponse create(
            @Valid @RequestBody CreateEntityPropertyMappingRequest request) {
        return EntityPropertyMappingResponse.from(service.create(request));
    }

    @GetMapping("/api/v1/entity-property-mappings")
    @PreAuthorize("@authz.isAdmin()")
    public PagedResponse<EntityPropertyMappingResponse> list(
            @RequestParam(required = false) EntityType entityType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        int cappedSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, cappedSize);
        return PagedResponse.from(
                service.list(entityType, pageable)
                        .map(EntityPropertyMappingResponse::from));
    }

    @GetMapping("/api/v1/entity-property-mappings/{id}")
    @PreAuthorize("@authz.isAdmin()")
    public EntityPropertyMappingResponse get(@PathVariable UUID id) {
        return EntityPropertyMappingResponse.from(service.get(id));
    }

    @PatchMapping("/api/v1/entity-property-mappings/{id}")
    @PreAuthorize("@authz.isAdmin()")
    public EntityPropertyMappingResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEntityPropertyMappingRequest request) {
        return EntityPropertyMappingResponse.from(service.update(id, request));
    }

    @DeleteMapping("/api/v1/entity-property-mappings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.isAdmin()")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
