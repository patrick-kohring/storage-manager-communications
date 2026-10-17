package com.storagemanager.communications.property;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagemanager.communications.dto.UpdateEntityPropertyMappingRequest;
import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;
import com.storagemanager.communications.service.EntityPropertyMappingService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.PropertyDefaults;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 4 — Partial-update semantics and timestamp invariants</b>.
 *
 * <p><b>Feature: entity-api, Property 4: Partial-update semantics and timestamp invariants</b> —
 * for an existing mapping and an update request where each field ({@code entityType},
 * {@code jsonProperty}, {@code placeholder}) is independently null or a fresh valid value (chosen to
 * avoid {@code (entityType, placeholder)} pair collisions), each non-null field replaces the stored
 * field and each null field is left unchanged; {@code createdAt} is unchanged and
 * {@code updatedAt >= original.updatedAt}.
 *
 * <p><b>Validates: Requirements 1.6, 1.7, 8.3, 8.4, 8.10</b>
 *
 * <h2>Approach</h2>
 * This is a <em>unit-level</em> property exercising
 * {@link EntityPropertyMappingService#update(UUID, UpdateEntityPropertyMappingRequest)} against an
 * in-memory fake {@link EntityPropertyMappingRepository}. The fake's {@code save(..)} mimics the JPA
 * lifecycle callbacks the real entity relies on: on first persist it assigns an id and sets
 * {@code createdAt}/{@code updatedAt}; on a subsequent save of an already-persisted row it refreshes
 * {@code updatedAt} (the {@code @PreUpdate} behavior) while <em>preserving</em> the stored
 * {@code createdAt} (the {@code @PrePersist}, {@code updatable = false} behavior). Because
 * {@link Instant#now()} resolution can land on the same tick, the fake advances {@code updatedAt}
 * to {@code max(now, storedUpdatedAt)} so the monotonic {@code updatedAt >= original.updatedAt}
 * invariant is observable without flakiness.
 *
 * <p>Each update field is generated independently as null-or-fresh. A "fresh" value is drawn to
 * differ from the stored value, and the fresh placeholder is kebab-valid and chosen from a space
 * disjoint from the seed placeholder so the prospective {@code (entityType, placeholder)} pair never
 * collides with the single existing row (there is exactly one row in the store).
 */
@PropertyDefaults(tries = 200, generation = GenerationMode.RANDOMIZED)
class EntityMappingUpdatePropertyTest {

    /**
     * Feature: entity-api, Property 4: Partial-update semantics and timestamp invariants.
     *
     * <p>For a seeded existing mapping and an independently null-or-fresh update request, each
     * non-null field replaces the stored field, each null field is left unchanged, {@code createdAt}
     * is never modified, and {@code updatedAt} is monotonically non-decreasing.
     *
     * <p><b>Validates: Requirements 1.6, 1.7, 8.3, 8.4, 8.10</b>
     */
    @Property
    void partialUpdateReplacesNonNullFieldsPreservesNullsAndTimestamps(
            @ForAll("seeds") Seed seed,
            @ForAll("updates") Update update) {

        FakeRepository repository = new FakeRepository();
        EntityPropertyMappingService service = new EntityPropertyMappingService(repository);

        // --- Seed a single existing mapping and capture its original state. ---
        EntityPropertyMapping seeded = new EntityPropertyMapping();
        seeded.setEntityType(seed.entityType());
        seeded.setJsonProperty(seed.jsonProperty());
        seeded.setPlaceholder(seed.placeholder());
        EntityPropertyMapping persisted = repository.save(seeded);

        UUID id = persisted.getId();
        EntityType originalEntityType = persisted.getEntityType();
        String originalJsonProperty = persisted.getJsonProperty();
        String originalPlaceholder = persisted.getPlaceholder();
        Instant originalCreatedAt = persisted.getCreatedAt();
        Instant originalUpdatedAt = persisted.getUpdatedAt();

        // --- Build the partial-update request: each field independently null-or-fresh. ---
        UpdateEntityPropertyMappingRequest request = new UpdateEntityPropertyMappingRequest(
                update.entityType(),
                update.jsonProperty(),
                update.placeholder());

        EntityPropertyMapping updated = service.update(id, request);

        // --- Non-null fields replace; null fields are left unchanged (Req 8.3, 8.4). ---
        EntityType expectedEntityType =
                update.entityType() != null ? update.entityType() : originalEntityType;
        String expectedJsonProperty =
                update.jsonProperty() != null ? update.jsonProperty() : originalJsonProperty;
        String expectedPlaceholder =
                update.placeholder() != null ? update.placeholder() : originalPlaceholder;

        assertThat(updated.getEntityType())
                .as("non-null entityType replaces; null leaves it unchanged")
                .isEqualTo(expectedEntityType);
        assertThat(updated.getJsonProperty())
                .as("non-null jsonProperty replaces; null leaves it unchanged")
                .isEqualTo(expectedJsonProperty);
        assertThat(updated.getPlaceholder())
                .as("non-null placeholder replaces; null leaves it unchanged")
                .isEqualTo(expectedPlaceholder);

        // --- createdAt is never modified by an update (Req 1.6). ---
        assertThat(updated.getCreatedAt())
                .as("createdAt is unchanged across an update")
                .isEqualTo(originalCreatedAt);

        // --- updatedAt is refreshed and monotonically non-decreasing (Req 1.7, 8.10). ---
        assertThat(updated.getUpdatedAt())
                .as("updatedAt >= original.updatedAt after an update")
                .isAfterOrEqualTo(originalUpdatedAt);

        // --- The change is reflected through a subsequent read of the same row. ---
        EntityPropertyMapping reread = service.get(id);
        assertThat(reread.getEntityType()).isEqualTo(expectedEntityType);
        assertThat(reread.getJsonProperty()).isEqualTo(expectedJsonProperty);
        assertThat(reread.getPlaceholder()).isEqualTo(expectedPlaceholder);
        assertThat(reread.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(reread.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
    }

    // =====================================================================
    // Generators
    // =====================================================================

    /** The seeded existing mapping. Placeholder is kebab-valid and drawn from the "seed" space. */
    @Provide
    Arbitrary<Seed> seeds() {
        return Combinators.combine(jsonProperties(), seedPlaceholders())
                .as((json, placeholder) -> new Seed(EntityType.LEAD, json, placeholder));
    }

    /**
     * An update where each field is independently null (~40%) or a fresh valid value. The fresh
     * placeholder is kebab-valid and drawn from the "fresh" space, which is disjoint from the seed
     * placeholder space, so the prospective pair never collides with the single existing row.
     */
    @Provide
    Arbitrary<Update> updates() {
        // EntityType has a single value (LEAD); a fresh value therefore equals the stored value,
        // which still exercises the "non-null entityType is applied" branch without a pair collision
        // (the placeholder space keeps the pair unique when the placeholder also changes).
        Arbitrary<EntityType> entityTypeOrNull = nullOr(Arbitraries.of(EntityType.LEAD));
        Arbitrary<String> jsonOrNull = nullOr(jsonProperties());
        Arbitrary<String> placeholderOrNull = nullOr(freshPlaceholders());
        return Combinators.combine(entityTypeOrNull, jsonOrNull, placeholderOrNull)
                .as(Update::new);
    }

    /** jsonProperty values: flat names, dot-paths, and odd/unicode strings, stored verbatim. */
    private Arbitrary<String> jsonProperties() {
        Arbitrary<String> flat = Arbitraries.of(
                "firstName", "lastName", "email", "phoneNumber", "status");
        Arbitrary<String> dotPath = Arbitraries.of(
                "address.city", "address.state", "contact.email", "lead.source.channel");
        Arbitrary<String> odd = Arbitraries.of(
                " spaced ", "with\ttab", "ünïcodë", "emoji-🎯", "{raw-braces}", "MiXeDcAsE");
        return Arbitraries.oneOf(flat, dotPath, odd);
    }

    /** Kebab-valid placeholders for the seed row (disjoint from the fresh space). */
    private Arbitrary<String> seedPlaceholders() {
        return Arbitraries.of(
                "first-name", "last-name", "email", "phone-number", "status", "seed-1", "seed-2");
    }

    /** Kebab-valid placeholders for update values (disjoint from the seed space, no collisions). */
    private Arbitrary<String> freshPlaceholders() {
        return Arbitraries.of(
                "fresh-a", "fresh-b", "fresh-c", "updated-placeholder", "new-value", "zz-9");
    }

    /** Wrap an arbitrary so it yields {@code null} ~40% of the time. */
    private <T> Arbitrary<T> nullOr(Arbitrary<T> inner) {
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(2, Arbitraries.just((T) null)),
                net.jqwik.api.Tuple.of(3, inner));
    }

    // =====================================================================
    // Value holders
    // =====================================================================

    record Seed(EntityType entityType, String jsonProperty, String placeholder) {}

    record Update(EntityType entityType, String jsonProperty, String placeholder) {}

    // =====================================================================
    // In-memory fake repository mimicking @PrePersist / @PreUpdate
    // =====================================================================

    /**
     * In-memory fake standing in for {@link EntityPropertyMappingRepository}. Only the methods the
     * service's update/get paths exercise are implemented; all other {@code JpaRepository} methods
     * throw {@link UnsupportedOperationException} to surface accidental reliance.
     *
     * <p>{@code save} mimics the entity lifecycle: a first save assigns an id and sets both
     * timestamps; a re-save refreshes {@code updatedAt} (to {@code max(now, stored)} to guarantee a
     * strictly observable monotonic advance) while preserving the stored {@code createdAt}.
     */
    static final class FakeRepository implements EntityPropertyMappingRepository {

        private final Map<UUID, EntityPropertyMapping> store = new HashMap<>();

        @Override
        public <S extends EntityPropertyMapping> S save(S entity) {
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
                Instant now = Instant.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
            } else {
                EntityPropertyMapping existing = store.get(entity.getId());
                Instant createdAt = existing != null ? existing.getCreatedAt() : entity.getCreatedAt();
                Instant priorUpdatedAt =
                        existing != null ? existing.getUpdatedAt() : entity.getUpdatedAt();
                entity.setCreatedAt(createdAt);
                Instant refreshed = Instant.now();
                if (priorUpdatedAt != null && !refreshed.isAfter(priorUpdatedAt)) {
                    refreshed = priorUpdatedAt.plusNanos(1);
                }
                entity.setUpdatedAt(refreshed);
            }
            store.put(entity.getId(), copyOf(entity));
            return entity;
        }

        @Override
        public Optional<EntityPropertyMapping> findById(UUID id) {
            EntityPropertyMapping found = store.get(id);
            return found == null ? Optional.empty() : Optional.of(copyOf(found));
        }

        @Override
        public boolean existsByEntityTypeAndPlaceholder(EntityType entityType, String placeholder) {
            return store.values().stream()
                    .anyMatch(m -> m.getEntityType() == entityType
                            && m.getPlaceholder().equals(placeholder));
        }

        @Override
        public boolean existsById(UUID id) {
            return store.containsKey(id);
        }

        private static EntityPropertyMapping copyOf(EntityPropertyMapping source) {
            EntityPropertyMapping copy = new EntityPropertyMapping();
            copy.setId(source.getId());
            copy.setEntityType(source.getEntityType());
            copy.setJsonProperty(source.getJsonProperty());
            copy.setPlaceholder(source.getPlaceholder());
            copy.setCreatedAt(source.getCreatedAt());
            copy.setUpdatedAt(source.getUpdatedAt());
            return copy;
        }

        // --- Unused JpaRepository surface ---------------------------------------------------

        @Override
        public org.springframework.data.domain.Page<EntityPropertyMapping> findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.Pageable pageable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<String> findPlaceholderByEntityType(EntityType entityType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.springframework.data.domain.Page<EntityPropertyMapping> findByEntityTypeOrderByCreatedAtDesc(
                EntityType entityType, org.springframework.data.domain.Pageable pageable) {
            throw new UnsupportedOperationException();
        }

        @Override public <S extends EntityPropertyMapping> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public List<EntityPropertyMapping> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<EntityPropertyMapping> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<EntityPropertyMapping> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public List<EntityPropertyMapping> findAllById(Iterable<UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public long count() { throw new UnsupportedOperationException(); }
        @Override public void deleteById(UUID id) { store.remove(id); }
        @Override public void delete(EntityPropertyMapping entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends EntityPropertyMapping> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public void flush() { throw new UnsupportedOperationException(); }
        @Override public <S extends EntityPropertyMapping> S saveAndFlush(S entity) { throw new UnsupportedOperationException(); }
        @Override public <S extends EntityPropertyMapping> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<EntityPropertyMapping> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public EntityPropertyMapping getOne(UUID id) { throw new UnsupportedOperationException(); }
        @Override public EntityPropertyMapping getById(UUID id) { throw new UnsupportedOperationException(); }
        @Override public EntityPropertyMapping getReferenceById(UUID id) { throw new UnsupportedOperationException(); }
        @Override public <S extends EntityPropertyMapping> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends EntityPropertyMapping> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends EntityPropertyMapping> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends EntityPropertyMapping> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends EntityPropertyMapping> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends EntityPropertyMapping> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends EntityPropertyMapping, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }
}
