package com.storagemanager.communications.inbox;

import com.storagemanager.communications.messaging.EventEnvelope;

/**
 * A handler that interprets an admitted inbound message of exactly one EventEnvelope eventType.
 * New entity actions register by implementing this interface as a Spring bean; the
 * Dispatching_Handler routes to them without modification (Req 1.1, 1.5, Decision D3).
 */
public interface InboxHandler {

    /**
     * The single eventType value this handler handles (Req 1.2, D3). Used as its Handler_Registry
     * key. Two handlers returning the same value fail application startup (Req 1.6).
     */
    String eventType();

    /**
     * Interpret a routed message (Req 1.3). Invoked by the Dispatching_Handler with the
     * EventEnvelope reconstructed from the InboundMessage. A normal return signals success; a
     * thrown exception is classified by the Dispatching_Handler's outcome mapping (Req 5).
     */
    void handle(EventEnvelope envelope);
}
