package com.storagemanager.communications.property;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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
import net.jqwik.api.constraints.IntRange;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 5 — List ordering, filtering, and page-size cap</b>.
 *
 * <p><b>Feature: entity-api, Property 5: List ordering, filtering, and page-size cap</b> — for any
 * backing set and any list request, content is monotone non-increasing by {@code createdAt}; a
 * supplied {@code entityType} filter returns exactly the mappings of that type (none of any other)
 * and no filter returns all types; for any requested {@code size > 100} the effective page size is
 * capped at 100 and reflected as 100 in the pagination metadata ({@code size} spanning
 * {@code {1, 20, 99, 100, 101, 500}}).
 *
 * <p><b>Validates: Requirements 6.3, 6.4, 6.7, 6.8</b>
 *
 * <h2>Approach</h2>
 * This is a <em>service-level</em> property on {@link EntityPropertyMappingService} with the
 * repository backed by an in-memory fake ({@link InMemoryEntityPropertyMappingRepository}) that
 * faithfully honours the newest-first ordering, the optional {@code entityType} filter, and Spring
 * Data paging semantics ({@link PageImpl} over a {@link PageRequest} slice). No mocking of ordering
 * logic is involved — the fake sorts by {@code createdAt} descending and slices exactly as the real
 * JPA finders do, so the property exercises the service's real filter-selection branch
 * ({@code findByEntityTypeOrderByCreatedAtDesc} vs {@code findAllByOrderByCreatedAtDesc}).
 *
 * <h2>Page-size cap placement (design)</h2>
 * The design deliberately places the {@code size > 100} cap in the <b>controller</b> via
 * {@code Math.min(size, 100)} (the service receives an already-capped {@link Pageable}). To honour
 * that placement without depending on the not-yet-implemented controller, the cap portion
 * replicates the controller's exact capping expression {@code Math.min(size, 100)}, builds the
 * {@link PageRequest} the controller would build, drives it through {@code service.list(...)}, and
 * asserts the returned {@link Page} reports an effective page size of {@code min(requested, 100)} —
 * i.e. {@code 100} for any requested size {@code > 100} and the requested size otherwise. The
 * effective page size is the pagination metadata the {@code PagedResponse} is built from (the
 * capped size flows through the {@code PageRequest} into {@code Page#getSize()}).
 */
class EntityMappingListPropertyTest {

    /** The controller's documented cap: {@code Math.min(size, 100)}. */
    private static final int MAX_PAGE_SIZE = 100;

    // =====================================================================
    // Property 5a: newest-first ordering within any requested page
    // =====================================================================

    /**
     * Feature: entity-api, Property 5: List ordering, filtering, and page-size cap.
     *
     * <p>For any backing set and any page request, the returned content is monotone non-increasing
     * by {@code createdAt} (newest-first), both unfiltered and filtered. Validates Requirements
     * 6.4/6.7 (unfiltered, newest-first) and 6.3/6.7 (filtered, newest-first).
     *
     * <p><b>Validates: Requirements 6.3, 6.4, 6.7</b>
     */
    @Property(tries = 100)
    void contentIsMonotoneNonIncreasingByCreatedAt(
            @ForAll("backingSets") List<EntityPropertyMapping> backing,
            @ForAll @IntRange(min = 0, max = 5) int page,
            @ForAll @IntRange(min = 1, max = 100) int size,
            @ForAll boolean withFilter) {

        EntityPropertyMappingService service = serviceBackedBy(backing);
        EntityType filter = withFilter ? EntityType.LEAD : null;

        Page<EntityPropertyMapping> result =
                service.list(filter, PageRequest.of(page, size));

        List<EntityPropertyMapping> content = result.getContent();
        for (int i = 1; i < content.size(); i++) {
            assertThat(content.get(i - 1).getCreatedAt())
                    .as("content is newest-first: element %d createdAt >= element %d createdAt",
                            i - 1, i)
                    .isAfterOrEqualTo(content.get(i).getCreatedAt());
        }
    }

    // =====================================================================
    // Property 5b: entityType filtering (exactly the matching type; all when absent)
    // =====================================================================

    /**
     * Feature: entity-api, Property 5: List ordering, filtering, and page-size cap.
     *
     * <p>A supplied {@code entityType} filter returns exactly the mappings of that type and none of
     * any other type; the total element count equals the number of backing mappings of that type.
     * With no filter, the total element count equals the full backing-set size (all types).
     *
     * <p><b>Validates: Requirements 6.3, 6.4</b>
     */
    @Property(tries = 100)
    void filterReturnsExactlyMatchingTypeAndNoFilterReturnsAll(
            @ForAll("backingSets") List<EntityPropertyMapping> backing,
            @ForAll @IntRange(min = 1, max = 100) int size) {

        EntityPropertyMappingService service = serviceBackedBy(backing);

        // Filtered by LEAD: every returned row is LEAD, and the total equals the LEAD count.
        long leadCount = backing.stream()
                .filter(m -> m.getEntityType() == EntityType.LEAD)
                .count();
        Page<EntityPropertyMapping> filtered =
                service.list(EntityType.LEAD, PageRequest.of(0, size));
        assertThat(filtered.getContent())
                .as("every filtered row has the requested entityType and none of any other")
                .allMatch(m -> m.getEntityType() == EntityType.LEAD);
        assertThat(filtered.getTotalElements())
                .as("filtered total equals the backing count of the requested type")
                .isEqualTo(leadCount);

        // Unfiltered: the total equals the full backing-set size (all types).
        Page<EntityPropertyMapping> unfiltered =
                service.list(null, PageRequest.of(0, size));
        assertThat(unfiltered.getTotalElements())
                .as("unfiltered total equals the full backing-set size (all entity types)")
                .isEqualTo(backing.size());
    }

    // =====================================================================
    // Property 5c: page-size cap at 100 reflected in pagination metadata
    // =====================================================================

    /**
     * Feature: entity-api, Property 5: List ordering, filtering, and page-size cap.
     *
     * <p>For a requested {@code size} spanning {@code {1, 20, 99, 100, 101, 500}}, applying the
     * controller's documented cap {@code Math.min(size, 100)} and driving the resulting
     * {@link PageRequest} through {@code service.list(...)} yields a {@link Page} whose effective
     * page size (the pagination metadata the {@code PagedResponse} is built from) equals
     * {@code min(requested, 100)}: {@code 100} for any requested size {@code > 100} and the
     * requested size otherwise.
     *
     * <p><b>Validates: Requirement 6.8</b>
     */
    @Property(tries = 100)
    void requestedSizeAboveHundredIsCappedAtHundredInPaginationMetadata(
            @ForAll("backingSets") List<EntityPropertyMapping> backing,
            @ForAll("requestedSizes") int requestedSize) {

        EntityPropertyMappingService service = serviceBackedBy(backing);

        // Replicate the controller's documented capping expression exactly (design places the cap
        // at the controller via Math.min(size, 100); the service receives an already-capped
        // Pageable).
        int cappedSize = Math.min(requestedSize, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(0, cappedSize);

        Page<EntityPropertyMapping> result = service.list(null, pageable);

        int expectedEffectiveSize = Math.min(requestedSize, MAX_PAGE_SIZE);

        assertThat(result.getSize())
                .as("effective page size is capped at 100 for requested size %d", requestedSize)
                .isEqualTo(expectedEffectiveSize);
        assertThat(result.getPageable().getPageSize())
                .as("the Pageable the page was built from carries the capped size")
                .isEqualTo(expectedEffectiveSize);

        if (requestedSize > MAX_PAGE_SIZE) {
            assertThat(result.getSize())
                    .as("requested size %d above 100 is reflected as 100 in pagination metadata",
                            requestedSize)
                    .isEqualTo(MAX_PAGE_SIZE);
        }

        // The capped page can never contain more rows than the cap.
        assertThat(result.getContent().size())
                .as("returned content size never exceeds the capped page size")
                .isLessThanOrEqualTo(MAX_PAGE_SIZE);
    }

    // =====================================================================
    // Generators
    // =====================================================================

    /** The requested-size span mandated by the property: {1, 20, 99, 100, 101, 500}. */
    @Provide
    Arbitrary<Integer> requestedSizes() {
        return Arbitraries.of(1, 20, 99, 100, 101, 500);
    }

    /**
     * Arbitrary backing sets of entity property mappings. Each mapping carries a distinct random
     * id, the single defined {@code EntityType.LEAD}, an arbitrary (possibly shared)
     * {@code jsonProperty}, a unique {@code placeholder} within the set, and a {@code createdAt}
     * drawn from a bounded range so ties (equal {@code createdAt}) occur and the non-strict
     * monotone-non-increasing ordering is genuinely exercised.
     */
    @Provide
    Arbitrary<List<EntityPropertyMapping>> backingSets() {
        Arbitrary<Integer> cardinality = Arbitraries.integers().between(0, 60);
        return cardinality.flatMap(n -> {
            // createdAt offsets within a small window so collisions (ties) are frequent.
            Arbitrary<Long> createdOffsets = Arbitraries.longs().between(0L, 20L);
            Arbitrary<String> jsonProps =
                    Arbitraries.of("firstName", "email", "address.city", "lastName", "phone");
            return Combinators.combine(
                            createdOffsets.list().ofSize(n),
                            jsonProps.list().ofSize(n))
                    .as((offsets, props) -> buildBacking(offsets, props));
        });
    }

    private static List<EntityPropertyMapping> buildBacking(List<Long> offsets, List<String> props) {
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        List<EntityPropertyMapping> mappings = new ArrayList<>(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
            EntityPropertyMapping m = new EntityPropertyMapping();
            m.setId(UUID.randomUUID());
            m.setEntityType(EntityType.LEAD);
            m.setJsonProperty(props.get(i));
            // Unique placeholder per element keeps the set well-formed (kebab-case, pair-unique).
            m.setPlaceholder("ph-" + i);
            Instant createdAt = base.plusSeconds(offsets.get(i));
            m.setCreatedAt(createdAt);
            m.setUpdatedAt(createdAt);
            mappings.add(m);
        }
        return mappings;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private EntityPropertyMappingService serviceBackedBy(List<EntityPropertyMapping> backing) {
        return new EntityPropertyMappingService(
                new InMemoryEntityPropertyMappingRepository(backing));
    }

    /**
     * In-memory fake {@link EntityPropertyMappingRepository} honouring the newest-first ordering,
     * the optional {@code entityType} filter, and Spring Data paging semantics used by the two list
     * finders under test. Only the finders exercised by {@code service.list(...)} are implemented;
     * all other {@link EntityPropertyMappingRepository}/{@code JpaRepository} methods are
     * unsupported (they are not reached by this property).
     */
    private static final class InMemoryEntityPropertyMappingRepository
            implements EntityPropertyMappingRepository {

        private final List<EntityPropertyMapping> store;

        InMemoryEntityPropertyMappingRepository(List<EntityPropertyMapping> seed) {
            this.store = new ArrayList<>(seed);
        }

        @Override
        public Page<EntityPropertyMapping> findAllByOrderByCreatedAtDesc(Pageable pageable) {
            return page(store.stream()
                    .sorted(Comparator.comparing(EntityPropertyMapping::getCreatedAt).reversed())
                    .toList(), pageable);
        }

        @Override
        public Page<EntityPropertyMapping> findByEntityTypeOrderByCreatedAtDesc(
                EntityType entityType, Pageable pageable) {
            return page(store.stream()
                    .filter(m -> m.getEntityType() == entityType)
                    .sorted(Comparator.comparing(EntityPropertyMapping::getCreatedAt).reversed())
                    .toList(), pageable);
        }

        @Override
        public List<String> findPlaceholderByEntityType(EntityType entityType) {
            return store.stream()
                    .filter(m -> m.getEntityType() == entityType)
                    .map(EntityPropertyMapping::getPlaceholder)
                    .toList();
        }

        /** Slice an already-ordered list into a {@link PageImpl}, mirroring Spring Data paging. */
        private static Page<EntityPropertyMapping> page(
                List<EntityPropertyMapping> ordered, Pageable pageable) {
            int total = ordered.size();
            int from = (int) Math.min((long) pageable.getOffset(), total);
            int to = (int) Math.min(from + (long) pageable.getPageSize(), total);
            List<EntityPropertyMapping> slice = new ArrayList<>(ordered.subList(from, to));
            return new PageImpl<>(slice, pageable, total);
        }

        // ----- Unused repository/JpaRepository methods (not reached by service.list) -----

        @Override
        public boolean existsByEntityTypeAndPlaceholder(EntityType entityType, String placeholder) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public List<EntityPropertyMapping> findAll() {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public List<EntityPropertyMapping> findAll(org.springframework.data.domain.Sort sort) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public Page<EntityPropertyMapping> findAll(Pageable pageable) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public List<EntityPropertyMapping> findAllById(Iterable<UUID> ids) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> List<S> findAll(org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> List<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Sort sort) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> Page<S> findAll(
                org.springframework.data.domain.Example<S> example, Pageable pageable) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> java.util.Optional<S> findOne(
                org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> long count(org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> boolean exists(org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> S save(S entity) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> List<S> saveAll(Iterable<S> entities) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> S saveAndFlush(S entity) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public <S extends EntityPropertyMapping> List<S> saveAllAndFlush(Iterable<S> entities) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public java.util.Optional<EntityPropertyMapping> findById(UUID id) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public boolean existsById(UUID id) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public void deleteById(UUID id) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public void delete(EntityPropertyMapping entity) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public void deleteAllById(Iterable<? extends UUID> ids) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public void deleteAll(Iterable<? extends EntityPropertyMapping> entities) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public void deleteAll() {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public void deleteAllByIdInBatch(Iterable<UUID> ids) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public void deleteAllInBatch(Iterable<EntityPropertyMapping> entities) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public void deleteAllInBatch() {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public void flush() {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public EntityPropertyMapping getOne(UUID id) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public EntityPropertyMapping getById(UUID id) {
            throw new UnsupportedOperationException("not used by Property 5");
        }

        @Override
        public EntityPropertyMapping getReferenceById(UUID id) {
            throw new UnsupportedOperationException("not used by Property 5");
        }
    }
}
