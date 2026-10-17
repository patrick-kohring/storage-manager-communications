package com.storagemanager.communications.inbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Immutable lookup of registered InboxHandlers keyed by their single declared eventType
 * (Req 1.4). Built once at startup from the InboxHandler beans Spring discovers. A duplicate
 * eventType is a configuration error that fails startup (Req 1.6).
 */
@Component
public class HandlerRegistry {

    private final Map<String, InboxHandler> byEventType;

    /**
     * @param handlers all InboxHandler beans in the context (Spring injects the full list)
     * @throws DuplicateHandlerRegistrationException if two handlers declare the same eventType,
     *         identifying the conflicting eventType value (Req 1.6)
     */
    public HandlerRegistry(List<InboxHandler> handlers) {
        Map<String, InboxHandler> map = new HashMap<>();
        for (InboxHandler handler : handlers) {
            String eventType = handler.eventType();
            InboxHandler existing = map.putIfAbsent(eventType, handler);
            if (existing != null) {
                throw new DuplicateHandlerRegistrationException(eventType, existing, handler);
            }
        }
        this.byEventType = Map.copyOf(map);
    }

    /** @return the handler for eventType, or empty for an Unmatched_Message (Req 6). */
    public Optional<InboxHandler> lookup(String eventType) {
        return Optional.ofNullable(byEventType.get(eventType));
    }
}
