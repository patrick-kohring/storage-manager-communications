package com.storagemanager.communications.property;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.storagemanager.communications.dto.CreateEntityPropertyMappingRequest;
import com.storagemanager.communications.dto.UpdateEntityPropertyMappingRequest;
import com.storagemanager.communications.exception.ConflictException;
import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;
import com.storagemanager.communications.service.EntityPropertyMappingService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property test proving the design's
 * <b>Correctness Property 1 — {@code (entityType, placeholder)} uniqueness invariant</b> for the
 * {@code entity-api} feature's {@link EntityPropertyMappingService}.
 *
 * <p><b>Feature: entity-api, Property 1: {@code (entityType, placeholder)} uniqueness invariant</b>
 * — over a sequence of create/update operations applied to an initially empty store, the following
 * invariants hold:
 * <ul>
 *   <li><b>Global pair uniqueness.</b> No two persisted mappings ever share a
 *       {@code (entityType, placeholder)} pair (Requirements 12.1, 12.4).</li>
 *   <li><b>Create conflict is rejecting and non-mutating.</b> A create whose
 *       {@code (entityType, placeholder)} pair already exists raises {@link ConflictException} and
 *       leaves the store unchanged (Requirements 4.5, 12.3, 12.5).</li>
 *   <li><b>Update/rename conflict is rejecting and non-mutating.</b> A partial update that would
 *       move an existing row onto a pair already held by a different row raises
 *       {@link ConflictException} and leaves the store unchanged (Requirements 8.9, 12.3).</li>
 *   <li><b>Fresh pair succeeds.</b> A create with a not-yet-present pair persists exactly one new
 *       row (Requirements 4.5, 12.1).</li>
 *   <li><b>Shared {@code jsonProperty} is permitted.</b> Two mappings with equal {@code jsonProperty}
 *       under distinct placeholders both persist — uniqueness is never enforced on
 *       {@code jsonProperty} (Requirements 1.9, 12.2).</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 1.9, 4.5, 8.9, 12.1, 12.2, 12.3, 12.5</b>
 *
 * <h2>Harness</h2>
 * The real {@link EntityPropertyMappingService} is exercised directly against an in-memory fake
 * {@link EntityPropertyMappingRepository} (see {@link InMemoryEntityPropertyMappingRepository})
 * rather than a Mockito mock — the fake implements the three persistence operations the service
 * touches ({@code existsByEntityTypeAndPlaceholder}, {@code save}, {@code findById},
 * {@code existsById}, {@code deleteById}) with the same observable semantics as the JPA-backed
 * repository, including assigning a generated {@code id} and {@code createdAt}/{@code updatedAt}
 * timestamps on first persist (mirroring the entity's {@code @PrePersist}). This keeps the test a
 * fast, deterministic unit property with no Spring context, while still driving the production
 * uniqueness pre-check logic end-to-end. Tries are set to 200 (above the design minimum of 100).
 */
class EntityMappingUniquenessPropertyTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // Property 1a — a mixed sequence of create/update ops never produces a duplicate
    //               (entityType, placeholder) pair; every ConflictException is a true
    //               no-op (store unchanged); every success grows/moves the store legally.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Replays an arbitrary sequence of create and update operations against a fresh, initially
     * empty store and asserts after <em>every</em> step that:
     * <ul>
     *   <li>the set of persisted {@code (entityType, placeholder)} pairs has no duplicates
     *       (Requirements 12.1, 12.4);</li>
     *   <li>when an operation threw {@link ConflictException}, the full store snapshot is identical
     *       to the pre-op snapshot — a conflict mutates nothing (Requirements 4.5, 8.9, 12.3,
     *       12.5);</li>
     *   <li>when a create succeeded, the pair it introduced was absent beforehand and exactly one
     *       row was added (Requirements 4.5, 12.1); and</li>
     *   <li>when an update succeeded, the row count is unchanged and the row's resulting pair is
     *       held by no other row (Requirement 8.9).</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 4.5, 8.9, 12.1, 12.3, 12.4, 12.5</b>
     */
    @Property(tries = 200)
    void sequenceOfOpsNeverCreatesDuplicatePairAndConflictsAreNoOps(
            @ForAll("opSequences") List<Op> ops) {

        InMemoryEntityPropertyMappingRepository repo =
                new InMemoryEntityPropertyMappingRepository();
        EntityPropertyMappingService service = new EntityPropertyMappingService(repo);

        for (Op op : ops) {
            Map<UUID, Pair> before = repo.snapshotPairs();

            if (op instanceof CreateOp create) {
                boolean pairPresentBefore = containsPair(before, create.type, create.placeholder);
                try {
                    EntityPropertyMapping saved = service.create(
                            new CreateEntityPropertyMappingRequest(
                                    create.type, create.jsonProperty, create.placeholder));

                    // Success path: the pair must have been absent, and exactly one row added.
                    assertThat(pairPresentBefore)
                            .as("a successful create must introduce a previously-absent pair")
                            .isFalse();
                    assertThat(repo.snapshotPairs()).hasSize(before.size() + 1);
                    assertThat(saved.getId()).as("persisted mapping has a generated id").isNotNull();
                } catch (ConflictException ex) {
                    // Conflict path: the pair existed, and NOTHING changed.
                    assertThat(pairPresentBefore)
                            .as("ConflictException on create implies the pair already existed")
                            .isTrue();
                    assertThat(repo.snapshotPairs())
                            .as("a create conflict leaves the store unchanged")
                            .isEqualTo(before);
                }
            } else if (op instanceof UpdateOp update) {
                List<UUID> ids = new ArrayList<>(before.keySet());
                if (ids.isEmpty()) {
                    continue; // nothing to update yet
                }
                UUID targetId = ids.get(Math.floorMod(update.targetIndex, ids.size()));
                Pair current = before.get(targetId);

                EntityType prospectiveType =
                        update.type != null ? update.type : current.type();
                String prospectivePlaceholder =
                        update.placeholder != null ? update.placeholder : current.placeholder();
                boolean collidesWithOther = before.entrySet().stream()
                        .anyMatch(e -> !e.getKey().equals(targetId)
                                && e.getValue().type() == prospectiveType
                                && e.getValue().placeholder().equals(prospectivePlaceholder));

                try {
                    service.update(targetId, new UpdateEntityPropertyMappingRequest(
                            update.type, update.jsonProperty, update.placeholder));

                    // Success path: no collision with a different row; row count unchanged.
                    assertThat(collidesWithOther)
                            .as("a successful update must not land on another row's pair")
                            .isFalse();
                    assertThat(repo.snapshotPairs()).hasSameSizeAs(before);
                } catch (ConflictException ex) {
                    // Conflict path: the resulting pair was held by another row, and NOTHING changed.
                    assertThat(collidesWithOther)
                            .as("ConflictException on update implies a collision with another row")
                            .isTrue();
                    assertThat(repo.snapshotPairs())
                            .as("an update conflict leaves the store unchanged")
                            .isEqualTo(before);
                }
            }

            // Global invariant after EVERY step: no duplicate (entityType, placeholder) pairs.
            assertNoDuplicatePairs(repo.snapshotPairs());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Property 1b — two mappings with EQUAL jsonProperty under DISTINCT placeholders
    //               (same entityType) both persist: jsonProperty is never a uniqueness key.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * For any shared {@code jsonProperty} value and any two <em>distinct</em> placeholders under
     * the single {@code LEAD} entity type, both creates succeed and both rows persist — proving the
     * store permits many placeholders to source the same property and never treats
     * {@code jsonProperty} as a uniqueness key.
     *
     * <p><b>Validates: Requirements 1.9, 12.2</b>
     */
    @Property(tries = 200)
    void equalJsonPropertyUnderDistinctPlaceholdersBothPersist(
            @ForAll("jsonProperties") String sharedJsonProperty,
            @ForAll("placeholders") String placeholderA,
            @ForAll("placeholders") String placeholderB) {

        if (placeholderA.equals(placeholderB)) {
            return; // precondition: the two placeholders must differ (distinct pairs)
        }

        InMemoryEntityPropertyMappingRepository repo =
                new InMemoryEntityPropertyMappingRepository();
        EntityPropertyMappingService service = new EntityPropertyMappingService(repo);

        EntityPropertyMapping first = service.create(new CreateEntityPropertyMappingRequest(
                EntityType.LEAD, sharedJsonProperty, placeholderA));
        EntityPropertyMapping second = service.create(new CreateEntityPropertyMappingRequest(
                EntityType.LEAD, sharedJsonProperty, placeholderB));

        assertThat(repo.snapshotPairs()).hasSize(2);
        assertThat(first.getJsonProperty()).isEqualTo(sharedJsonProperty);
        assertThat(second.getJsonProperty()).isEqualTo(sharedJsonProperty);
        assertThat(first.getPlaceholder()).isNotEqualTo(second.getPlaceholder());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers / invariant checks
    // ─────────────────────────────────────────────────────────────────────────────

    private static boolean containsPair(Map<UUID, Pair> store, EntityType type, String placeholder) {
        return store.values().stream()
                .anyMatch(p -> p.type() == type && p.placeholder().equals(placeholder));
    }

    private static void assertNoDuplicatePairs(Map<UUID, Pair> store) {
        List<Pair> pairs = new ArrayList<>(store.values());
        assertThat(pairs)
                .as("no two persisted mappings share a (entityType, placeholder) pair")
                .doesNotHaveDuplicates();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Generators
    // ─────────────────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<String> placeholders() {
        // Valid kebab-case names drawn from a small pool so collisions are common enough to
        // exercise both the fresh-pair and conflict branches densely.
        return Arbitraries.of(
                "first-name", "last-name", "email", "phone", "address-city",
                "a", "b", "c", "x1", "unit-number");
    }

    @Provide
    Arbitrary<String> jsonProperties() {
        return Arbitraries.of(
                "firstName", "lastName", "email", "address.city", "phone",
                "contact.primary.email", "weird\u00e9name", "a");
    }

    @Provide
    Arbitrary<Op> createOps() {
        return Combinators.combine(jsonProperties(), placeholders())
                .as((json, placeholder) -> new CreateOp(EntityType.LEAD, json, placeholder));
    }

    @Provide
    Arbitrary<Op> updateOps() {
        Arbitrary<EntityType> nullableType = Arbitraries.of(EntityType.LEAD, null);
        Arbitrary<String> nullableJson = Arbitraries.oneOf(
                jsonProperties(), Arbitraries.just(null));
        Arbitrary<String> nullablePlaceholder = Arbitraries.oneOf(
                placeholders(), Arbitraries.just(null));
        Arbitrary<Integer> indices = Arbitraries.integers().between(0, 20);
        return Combinators.combine(indices, nullableType, nullableJson, nullablePlaceholder)
                .as(UpdateOp::new);
    }

    @Provide
    Arbitrary<List<Op>> opSequences() {
        return Arbitraries.oneOf(createOps(), updateOps()).list().ofMinSize(1).ofMaxSize(30);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Operation model
    // ─────────────────────────────────────────────────────────────────────────────

    sealed interface Op permits CreateOp, UpdateOp {}

    record CreateOp(EntityType type, String jsonProperty, String placeholder) implements Op {}

    record UpdateOp(int targetIndex, EntityType type, String jsonProperty, String placeholder)
            implements Op {}

    /** A persisted mapping's uniqueness-relevant pair, used for store snapshots. */
    record Pair(EntityType type, String placeholder) {}

    // ─────────────────────────────────────────────────────────────────────────────
    // In-memory fake repository — implements only the operations the service touches,
    // with the same observable semantics as the JPA-backed repository (generated id and
    // @PrePersist timestamps on first save). All other JpaRepository methods are
    // unsupported and never invoked by the service under test.
    // ─────────────────────────────────────────────────────────────────────────────

    static final class InMemoryEntityPropertyMappingRepository
            implements EntityPropertyMappingRepository {

        private final Map<UUID, EntityPropertyMapping> store = new LinkedHashMap<>();

        Map<UUID, Pair> snapshotPairs() {
            Map<UUID, Pair> snapshot = new LinkedHashMap<>();
            store.forEach((id, m) -> snapshot.put(id, new Pair(m.getEntityType(), m.getPlaceholder())));
            return snapshot;
        }

        @Override
        public boolean existsByEntityTypeAndPlaceholder(EntityType entityType, String placeholder) {
            return store.values().stream()
                    .anyMatch(m -> m.getEntityType() == entityType
                            && m.getPlaceholder().equals(placeholder));
        }

        @Override
        public <S extends EntityPropertyMapping> S save(S entity) {
            if (entity.getId() == null) {
                // New row: assign id and run the @PrePersist timestamp semantics.
                entity.setId(UUID.randomUUID());
                Instant now = Instant.now();
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
            } else {
                // Existing row: refresh updatedAt like @PreUpdate, keep createdAt.
                entity.setUpdatedAt(Instant.now());
            }
            store.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public Optional<EntityPropertyMapping> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsById(UUID id) {
            return store.containsKey(id);
        }

        @Override
        public void deleteById(UUID id) {
            store.remove(id);
        }

        // ── Unused JpaRepository surface — not exercised by the service under test ──

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by the service under test");
        }

        @Override
        public Page<EntityPropertyMapping> findAllByOrderByCreatedAtDesc(Pageable pageable) {
            throw unused();
        }

        @Override
        public java.util.List<String> findPlaceholderByEntityType(EntityType entityType) {
            throw unused();
        }

        @Override
        public Page<EntityPropertyMapping> findByEntityTypeOrderByCreatedAtDesc(
                EntityType entityType, Pageable pageable) {
            throw unused();
        }

        @Override
        public List<EntityPropertyMapping> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<EntityPropertyMapping> findAll(Sort sort) {
            throw unused();
        }

        @Override
        public Page<EntityPropertyMapping> findAll(Pageable pageable) {
            return new PageImpl<>(new ArrayList<>(store.values()));
        }

        @Override
        public List<EntityPropertyMapping> findAllById(Iterable<UUID> ids) {
            throw unused();
        }

        @Override
        public <S extends EntityPropertyMapping> List<S> saveAll(Iterable<S> entities) {
            List<S> saved = new ArrayList<>();
            for (S e : entities) {
                saved.add(save(e));
            }
            return saved;
        }

        @Override
        public long count() {
            return store.size();
        }

        @Override
        public void delete(EntityPropertyMapping entity) {
            if (entity.getId() != null) {
                store.remove(entity.getId());
            }
        }

        @Override
        public void deleteAllById(Iterable<? extends UUID> ids) {
            ids.forEach(store::remove);
        }

        @Override
        public void deleteAll(Iterable<? extends EntityPropertyMapping> entities) {
            entities.forEach(this::delete);
        }

        @Override
        public void deleteAll() {
            store.clear();
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public <S extends EntityPropertyMapping> S saveAndFlush(S entity) {
            return save(entity);
        }

        @Override
        public <S extends EntityPropertyMapping> List<S> saveAllAndFlush(Iterable<S> entities) {
            return saveAll(entities);
        }

        @Override
        public void deleteAllInBatch(Iterable<EntityPropertyMapping> entities) {
            throw unused();
        }

        @Override
        public void deleteAllByIdInBatch(Iterable<UUID> ids) {
            throw unused();
        }

        @Override
        public void deleteAllInBatch() {
            store.clear();
        }

        @Override
        public EntityPropertyMapping getOne(UUID id) {
            throw unused();
        }

        @Override
        public EntityPropertyMapping getById(UUID id) {
            throw unused();
        }

        @Override
        public EntityPropertyMapping getReferenceById(UUID id) {
            throw unused();
        }

        @Override
        public <S extends EntityPropertyMapping> Optional<S> findOne(Example<S> example) {
            throw unused();
        }

        @Override
        public <S extends EntityPropertyMapping> List<S> findAll(Example<S> example) {
            throw unused();
        }

        @Override
        public <S extends EntityPropertyMapping> List<S> findAll(Example<S> example, Sort sort) {
            throw unused();
        }

        @Override
        public <S extends EntityPropertyMapping> Page<S> findAll(Example<S> example, Pageable pageable) {
            throw unused();
        }

        @Override
        public <S extends EntityPropertyMapping> long count(Example<S> example) {
            throw unused();
        }

        @Override
        public <S extends EntityPropertyMapping> boolean exists(Example<S> example) {
            throw unused();
        }

        @Override
        public <S extends EntityPropertyMapping, R> R findBy(
                Example<S> example,
                Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            throw unused();
        }
    }
}
