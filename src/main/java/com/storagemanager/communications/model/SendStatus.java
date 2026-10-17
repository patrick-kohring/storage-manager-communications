package com.storagemanager.communications.model;

/**
 * Controlled enumeration of the outcome state of a sent communication.
 *
 * <p>In this spec the enumeration defines exactly one value, {@code DISPATCHED}, which is the
 * default outcome state. Additional values may be added later as a pure code change — nothing is
 * keyed off the cardinality of this enum.
 *
 * <p>Persisted via {@code @Enumerated(EnumType.STRING)} so the stored column holds the literal
 * name (for example {@code DISPATCHED}). The entity and the request/response DTOs reference this
 * type directly, so Jackson handles string&lt;-&gt;enum binding and an unknown string fails as a
 * body-readability {@code 400}.
 */
public enum SendStatus {
    DISPATCHED
}
