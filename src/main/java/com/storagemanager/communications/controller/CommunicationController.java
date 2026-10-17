package com.storagemanager.communications.controller;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storagemanager.communications.model.CommunicationTemplateResponse;
import com.storagemanager.communications.model.CreateCommunicationTemplateRequest;
import com.storagemanager.communications.model.PagedResponse;
import com.storagemanager.communications.model.UpdateCommunicationTemplateRequest;
import com.storagemanager.communications.service.CommunicationService;

import jakarta.validation.Valid;

@RestController
public class CommunicationController {

    private final CommunicationService communicationService;

    public CommunicationController(CommunicationService communicationService) {
        this.communicationService = communicationService;
    }

    @PostMapping("/api/v1/communication-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.isAdmin() or @authz.hasRole('OWNER')")
    public CommunicationTemplateResponse create(
            @Valid @RequestBody CreateCommunicationTemplateRequest request) {
        return CommunicationTemplateResponse.from(communicationService.create(request));
    }

    @GetMapping("/api/v1/communication-templates")
    @PreAuthorize("@authz.isAdmin() or @authz.hasRole('OWNER')")
    public PagedResponse<CommunicationTemplateResponse> list(
            @RequestParam(required = false) UUID organizationId,
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
                communicationService.list(organizationId, pageable)
                        .map(CommunicationTemplateResponse::from));
    }

    @GetMapping("/api/v1/communication-templates/{id}")
    @PreAuthorize("@authz.isAdmin() or @authz.hasRole('OWNER')")
    public CommunicationTemplateResponse get(@PathVariable UUID id) {
        return CommunicationTemplateResponse.from(communicationService.get(id));
    }

    @PatchMapping("/api/v1/communication-templates/{id}")
    @PreAuthorize("@authz.isAdmin() or @authz.hasRole('OWNER')")
    public CommunicationTemplateResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCommunicationTemplateRequest request) {
        return CommunicationTemplateResponse.from(communicationService.update(id, request));
    }
}
