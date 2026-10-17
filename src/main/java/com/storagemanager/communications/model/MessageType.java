package com.storagemanager.communications.model;

/**
 * Controlled enumeration of the delivery channel of a communication.
 *
 * <p>Defines the two supported channels, {@code EMAIL} and {@code SMS}. Additional channels may be
 * added later as a pure code change.
 *
 * <p>Persisted via {@code @Enumerated(EnumType.STRING)} so the stored column holds the literal
 * name (for example {@code EMAIL}). The entity and the request/response DTOs reference this type
 * directly, so Jackson handles string&lt;-&gt;enum binding and an unknown string fails as a
 * body-readability {@code 400}.
 */
public enum MessageType {
    EMAIL,
    SMS
}
