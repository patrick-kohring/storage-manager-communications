package com.storagemanager.communications.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.service.InboundRecordingService;

import jakarta.validation.Valid;

/**
 * Generic ingest endpoint for the messaging base (Req 3). Accepts a canonical EventEnvelope,
 * applies the service-to-service authorization posture mirrored from events (Req 4), and records
 * the message idempotently via the starter IdempotentInbox (Req 5). Returns 200 for both a newly
 * accepted and a duplicate message (Req 3.6). Does NOT branch on eventType (Req 3.3).
 */
@RestController
public class InboundEventController {

    private final InboundRecordingService recordingService;
    private final IngestAuthorizer ingestAuthorizer;

    public InboundEventController(InboundRecordingService recordingService,
                                  IngestAuthorizer ingestAuthorizer) {
        this.recordingService = recordingService;
        this.ingestAuthorizer = ingestAuthorizer;
    }

    @PostMapping("/api/v1/communications")
    @ResponseStatus(HttpStatus.OK) // 200 for both new and duplicate (Req 3.6)
    public void ingest(@Valid @RequestBody EventEnvelope envelope, Authentication authentication) {
        // Resolve the authoritative organization scope per the S2S rule (Req 4.3-4.5).
        // Throws AccessDeniedException (-> 403) for a cross-org End_User_Token.
        UUID organizationScope = ingestAuthorizer.resolveOrganizationScope(envelope, authentication);
        recordingService.record(envelope, organizationScope);
    }
}
