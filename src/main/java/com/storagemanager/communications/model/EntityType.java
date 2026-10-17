package com.storagemanager.communications.model;

/**
 * Controlled enumeration of upstream domain entity types that an entity property mapping
 * can apply to.
 *
 * <p>In this spec the enumeration defines exactly one value, {@code LEAD}. Additional values
 * may be added later as a pure code change — nothing is keyed off the cardinality of this enum.
 *
 * <p>Persisted via {@code @Enumerated(EnumType.STRING)} so the stored column holds the literal
 * name (for example {@code LEAD}). The entity and the request/response DTOs reference this type
 * directly, so Jackson handles string&lt;-&gt;enum binding and an unknown string fails as a
 * body-readability {@code 400}.
 */
public enum EntityType {
    LEAD
}
