package com.storagemanager.communications.service;

/**
 * Immutable outcome of a {@link DeliveryProvider#dispatch(DeliveryRequest)} attempt.
 *
 * <p>Reports whether the dispatch succeeded, the provider identifier that handled it, the
 * provider-assigned message id, and a failure reason when the dispatch was unsuccessful.
 *
 * @param providerId        the identifier of the provider that handled the dispatch
 * @param success           whether the dispatch succeeded
 * @param providerMessageId the provider-assigned message id (nullable)
 * @param failureReason     the reason the dispatch failed (nullable; null on success)
 */
public record DeliveryResult(
        boolean success,
        String providerId,
        String providerMessageId,
        String failureReason) {
}
