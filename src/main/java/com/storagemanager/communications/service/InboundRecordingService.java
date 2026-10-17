package com.storagemanager.communications.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;

/**
 * Maps a validated EventEnvelope onto the starter InboundMessage and records it idempotently
 * through processOnce with the injected inbox handler seam (Req 5). The idempotency key is the
 * envelope eventId, used as the inbox message_id (Req 5.1, 8.4).
 *
 * <p>The {@code Consumer<InboundMessage>} handler seam is constructor-injected rather than inlined:
 * the {@code inbox-action-dispatch} spec supplies the {@code DispatchingHandler} (a {@code @Component},
 * hence the sole {@code Consumer<InboundMessage>} bean) as the seam, routing admitted messages to
 * their registered handlers through {@code processOnce} (Req 2.1). This is the one messaging-owned
 * file that spec edits; the EventEnvelope-to-InboundMessage mapping and the dedupe/stamp mechanics
 * are unchanged (Req 5.6).
 */
@Service
public class InboundRecordingService {

    private final IdempotentInbox inbox;
    private final Consumer<InboundMessage> inboxHandler;

    public InboundRecordingService(IdempotentInbox inbox, Consumer<InboundMessage> inboxHandler) {
        this.inbox = inbox;
        this.inboxHandler = inboxHandler;
    }

    @Transactional
    public void record(EventEnvelope envelope, UUID organizationScope) {
        InboundMessage message = toInboundMessage(envelope, organizationScope);
        // Route the admitted message through the injected handler seam (Req 2.1). processOnce keeps
        // its dedupe/insert-on-receive/processed_at mechanics unchanged (Req 5.6); the seam decides
        // whether to complete normally (stamp) or throw (redeliver).
        inbox.processOnce(message, inboxHandler);
    }

    private InboundMessage toInboundMessage(EventEnvelope e, UUID organizationScope) {
        // scope_0 = organization (authoritative on ingest), scope_1 = facility (nullable).
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put("organization_id", organizationScope);
        scope.put("facility_id", e.facilityId()); // null -> scope_1 null, no sentinel (Req 9.3)

        // The seven non-promoted envelope fields ride as passthrough headers; actorType/actorId
        // are preserved here, NOT overwritten by the transport identity (Req 4.6).
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", Integer.toString(e.schemaVersion()));
        headers.put("occurred_at", e.occurredAt().toString());
        headers.put("entity_type", e.entityType());
        headers.put("entity_id", e.entityId().toString());
        headers.put("actor_type", e.actorType().name());
        if (e.actorId() != null) {
            headers.put("actor_id", e.actorId().toString());
        }
        if (e.causationId() != null) {
            headers.put("causation_id", e.causationId().toString());
        }

        return new InboundMessage(
                e.eventId(),        // messageId == eventId (the Idempotency_Key, Req 5.1, 8.4)
                e.eventType(),
                (Map<String, Object>) e.payload(),
                scope,
                e.correlationId(),
                headers);
    }
}
