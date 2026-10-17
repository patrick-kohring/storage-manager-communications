package com.storagemanager.communications.service;

import com.storagemanager.communications.model.MessageType;

/**
 * Provider-interface seam abstracting third-party email/SMS delivery.
 *
 * <p>Each implementation handles exactly one {@link MessageType} channel, reported by
 * {@link #channel()}, and performs a single delivery via {@link #dispatch(DeliveryRequest)}.
 *
 * <p>In this spec every implementation is a stub that performs no external third-party
 * transmission and reports a successful dispatch outcome. Swapping a stub for a real vendor
 * adapter later is a pure addition of a new {@code DeliveryProvider} bean with no change to the
 * calling service.
 */
public interface DeliveryProvider {

    /**
     * The delivery channel this provider handles.
     *
     * @return {@link MessageType#EMAIL} or {@link MessageType#SMS}
     */
    MessageType channel();

    /**
     * Dispatches the given request through this provider's channel.
     *
     * @param request the delivery request describing the recipient contact and message content
     * @return the outcome of the dispatch attempt
     */
    DeliveryResult dispatch(DeliveryRequest request);
}
