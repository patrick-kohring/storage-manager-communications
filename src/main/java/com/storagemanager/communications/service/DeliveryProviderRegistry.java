package com.storagemanager.communications.service;

import java.util.Collection;
import java.util.EnumMap;

import org.springframework.stereotype.Component;

import com.storagemanager.communications.model.MessageType;

/**
 * Registry that selects the {@link DeliveryProvider} for a given {@link MessageType} channel.
 *
 * <p>All {@code DeliveryProvider} beans are received via constructor injection and indexed by their
 * {@link DeliveryProvider#channel()} into an {@link EnumMap}. The {@link SendService} resolves the
 * provider for a request's message type via {@link #providerFor(MessageType)} (Requirement 1.2).
 *
 * <p>Two providers claiming the same channel is a configuration error and fails fast during
 * construction. Likewise, requesting a provider for a channel with no registered implementation is a
 * configuration error (not reachable given both stub providers exist).
 */
@Component
public class DeliveryProviderRegistry {

    private final EnumMap<MessageType, DeliveryProvider> providersByChannel =
            new EnumMap<>(MessageType.class);

    /**
     * Builds the registry by indexing every injected {@link DeliveryProvider} bean by its channel.
     *
     * @param providers all {@code DeliveryProvider} beans in the application context
     * @throws IllegalStateException if two providers claim the same channel
     */
    public DeliveryProviderRegistry(Collection<DeliveryProvider> providers) {
        for (DeliveryProvider provider : providers) {
            MessageType channel = provider.channel();
            DeliveryProvider existing = providersByChannel.putIfAbsent(channel, provider);
            if (existing != null) {
                throw new IllegalStateException(
                        "Multiple DeliveryProvider beans registered for channel " + channel
                                + ": " + existing.getClass().getName()
                                + " and " + provider.getClass().getName());
            }
        }
    }

    /**
     * Returns the {@link DeliveryProvider} registered for the given channel.
     *
     * @param type the delivery channel to resolve a provider for
     * @return the provider handling that channel
     * @throws IllegalStateException if no provider is registered for the type (a configuration error)
     */
    public DeliveryProvider providerFor(MessageType type) {
        DeliveryProvider provider = providersByChannel.get(type);
        if (provider == null) {
            throw new IllegalStateException(
                    "No DeliveryProvider registered for channel " + type);
        }
        return provider;
    }
}
