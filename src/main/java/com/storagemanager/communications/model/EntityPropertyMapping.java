package com.storagemanager.communications.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * JPA entity representing a single translation from an upstream domain entity JSON property
 * to a kebab-case template placeholder name used later for template personalization.
 *
 * <p>Each mapping's {@code (entityType, placeholder)} combination is unique, enforced by the
 * {@code uq_entity_prop_mapping_type_placeholder} database-level unique constraint. Many
 * placeholders MAY map to the same {@code jsonProperty}; uniqueness is deliberately not enforced
 * on {@code jsonProperty}.
 *
 * <p>The {@code jsonProperty} value is stored exactly as supplied — it is not interpreted,
 * traversed, or validated against any upstream entity shape. The {@code placeholder} value holds
 * the inner name only, without the surrounding {@code &#123;&#123;} / {@code &#125;&#125;}
 * delimiters.
 */
@Entity
@Table(
    name = "entity_property_mappings",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_entity_prop_mapping_type_placeholder",
        columnNames = {"entity_type", "placeholder"}
    )
)
public class EntityPropertyMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private EntityType entityType;

    @Column(name = "json_property", nullable = false)
    private String jsonProperty;

    @Column(name = "placeholder", nullable = false)
    private String placeholder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public EntityType getEntityType() { return entityType; }
    public void setEntityType(EntityType entityType) { this.entityType = entityType; }

    public String getJsonProperty() { return jsonProperty; }
    public void setJsonProperty(String jsonProperty) { this.jsonProperty = jsonProperty; }

    public String getPlaceholder() { return placeholder; }
    public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
