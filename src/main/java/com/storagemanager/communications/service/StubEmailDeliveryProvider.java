package com.storagemanager.communications.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.storagemanager.communications.model.MessageType;

/**
 * No-op {@link DeliveryProvider} stub for the {@link MessageType#EMAIL} channel.
 *
 * <p>Per the Communications API spec, this stub performs no external third-party transmission
 * and always reports a successful dispatch outcome (Requirement 1.3). The provider message id is
 * generated locally with no network calls. Swapping this stub for a real email vendor adapter later
 * is a pure addition of a new {@code DeliveryProvider} bean with no change to the calling service.
 */
@Component
public class StubEmailDeliveryProvider implements DeliveryProvider {

    private static final String PROVIDER_ID = "stub-email";

    @Override
    public MessageType channel() {
        return MessageType.EMAIL;
    }

    @Override
    public DeliveryResult dispatch(DeliveryRequest request) {
        // No external I/O: report a successful dispatch with a locally generated provider message id.
        String providerMessageId = PROVIDER_ID + "-" + UUID.randomUUID();
        return new DeliveryResult(true, PROVIDER_ID, providerMessageId, null);
    }
}
