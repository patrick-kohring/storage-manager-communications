package com.storagemanager.communications.controller;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storagemanager.communications.dto.SentCommunicationResponse;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.PagedResponse;
import com.storagemanager.communications.model.SendCommunicationRequest;
import com.storagemanager.communications.service.SendService;

import jakarta.validation.Valid;

/**
 * REST controller for the send-and-log (Communications API) feature, rooted at
 * {@code /api/v1/sent-communications}.
 *
 * <p>Mirrors the existing {@code CommunicationController} (templates) conventions: constructor
 * injection, a {@code @PreAuthorize("@authz...")} gate on every endpoint so the role check runs
 * before any business logic (Requirement 7.3), and the identical page/size validation + cap pattern
 * and {@code PagedResponse.from(...map(...))} usage for the list endpoint.
 *
 * <p>This is the plural {@code CommunicationsController}, rooted at {@code /api/v1/sent-communications}.
 * It is distinct from the singular {@code CommunicationController} at
 * {@code /api/v1/communication-templates} and from the messaging {@code InboundEventController} at
 * {@code POST /api/v1/communications} — the paths do not collide.
 */
@RestController
public class CommunicationsController {

    private final SendService service;

    public CommunicationsController(SendService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/sent-communications")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.isAdmin() or @authz.hasRole('OWNER')")
    public SentCommunicationResponse send(@Valid @RequestBody SendCommunicationRequest request) {
        return SentCommunicationResponse.from(service.send(request));
    }

    @GetMapping("/api/v1/sent-communications/{id}")
    @PreAuthorize("@authz.isAdmin() or @authz.hasRole('OWNER')")
    public SentCommunicationResponse get(@PathVariable UUID id) {
        return SentCommunicationResponse.from(service.get(id));
    }

    @GetMapping("/api/v1/sent-communications")
    @PreAuthorize("@authz.isAdmin() or @authz.hasRole('OWNER')")
    public PagedResponse<SentCommunicationResponse> list(
            @RequestParam(required = false) UUID facilityId,
            @RequestParam(required = false) MessageType messageType,
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
                service.list(facilityId, messageType, pageable)
                        .map(SentCommunicationResponse::from));
    }
}
