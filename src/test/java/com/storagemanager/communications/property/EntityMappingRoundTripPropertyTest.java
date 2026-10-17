package com.storagemanager.communications.property;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storagemanager.communications.dto.CreateEntityPropertyMappingRequest;
import com.storagemanager.communications.dto.EntityPropertyMappingResponse;
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
 * jqwik property test for {@link EntityPropertyMappingService} create-then-get round-trip fidelity
 * (entity-api spec, Task 6.8).
 *
 * <p><strong>Feature: entity-api, Property 3: Create-then-get round-trip fidelity.</strong>
 * For any valid create request — with {@code jsonProperty} spanning flat names, dot-paths, and odd
 * / unicode strings, and {@code placeholder} a valid kebab-case string so it passes the create
 * contract — {@code get(create(req).id)} returns a mapping whose {@code entityType},
 * {@code jsonProperty}, and {@code placeholder} equal the supplied request values field-for-field,
 * with {@code jsonProperty} stored verbatim (no interpretation, traversal, or validation). The
 * persisted {@code id}, {@code createdAt}, and {@code updatedAt} are all non-null, and
 * {@link EntityPropertyMappingResponse#from} carries each field through unchanged.
 *
 * <p>The repository is backed by an in-memory fake (a Mockito mock whose {@code save},
 * {@code findById}, and {@code existsByEntityTypeAndPlaceholder} are stubbed against a backing
 * {@link Map}). On {@code save} the fake assigns a {@code UUID} id and sets {@code createdAt} /
 * {@code updatedAt} to {@code Instant.now()}, mimicking the entity's {@code @PrePersist} callback
 * that the real JPA provider would invoke.
 *
 * <p><strong>Validates: Requirements 1.2, 1.4, 4.2, 4.4, 4.6, 7.2, 10.1, 10.2, 14.2, 14.3</strong>
 */
class EntityMappingRoundTripPropertyTest {

    // ------------------------------------------------------------------------
    // Property 3: create-then-get round-trip fidelity
    // ------------------------------------------------------------------------

    /**
     * For any valid create request, {@code get(create(req).id)} reproduces the supplied
     * {@code entityType} / {@code jsonProperty} / {@code placeholder} exactly, with
     * {@code jsonProperty} stored verbatim; {@code id} / {@code createdAt} / {@code updatedAt} are
     * non-null; and {@link EntityPropertyMappingResponse#from} carries every field through
     * unchanged.
     *
     * <p>Validates: Requirements 1.2, 1.4, 4.2, 4.4, 4.6, 7.2, 10.1, 10.2, 14.2, 14.3
     */
    @Property(tries = 100)
    void createThenGet_roundTripsEveryFieldFaithfully(
            @ForAll("createRequests") CreateEntityPropertyMappingRequest request) {

        EntityPropertyMappingService service = new EntityPropertyMappingService(newFakeRepository());

        // create
        EntityPropertyMapping created = service.create(request);

        // id / timestamps assigned on persist are non-null (Req 1.2, 4.6)
        assertThat(created.getId()).as("id assigned on persist").isNotNull();
        assertThat(created.getCreatedAt()).as("createdAt set on persist").isNotNull();
        assertThat(created.getUpdatedAt()).as("updatedAt set on persist").isNotNull();

        // get-by-id returns the persisted mapping (Req 7.2)
        EntityPropertyMapping fetched = service.get(created.getId());
        assertThat(fetched).as("get returns the created row").isSameAs(created);

        // field-for-field fidelity, jsonProperty stored verbatim (Req 4.2, 4.4, 14.2, 14.3)
        assertThat(fetched.getEntityType()).isEqualTo(request.entityType());
        assertThat(fetched.getJsonProperty()).isEqualTo(request.jsonProperty());
        assertThat(fetched.getPlaceholder()).isEqualTo(request.placeholder());

        // the fetched row carries the same non-null id and timestamps
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getCreatedAt()).isNotNull();
        assertThat(fetched.getUpdatedAt()).isNotNull();

        // EntityPropertyMappingResponse.from carries every field through unchanged (Req 10.1, 10.2)
        EntityPropertyMappingResponse response = EntityPropertyMappingResponse.from(fetched);
        assertThat(response.id()).isEqualTo(fetched.getId());
        assertThat(response.entityType()).isEqualTo(request.entityType());
        assertThat(response.jsonProperty()).isEqualTo(request.jsonProperty());
        assertThat(response.placeholder()).isEqualTo(request.placeholder());
        assertThat(response.createdAt()).isEqualTo(fetched.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(fetched.getUpdatedAt());
    }

    // ------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------

    /**
     * Valid create requests. {@code entityType} is the single defined value {@code LEAD};
     * {@code jsonProperty} spans flat names, dot-paths, and odd/unicode strings; {@code placeholder}
     * is a valid kebab-case string so the create contract accepts it.
     */
    @Provide
    Arbitrary<CreateEntityPropertyMappingRequest> createRequests() {
        return Combinators.combine(
                        Arbitraries.of(EntityType.values()),
                        jsonProperties(),
                        kebabPlaceholders())
                .as(CreateEntityPropertyMappingRequest::new);
    }

    /**
     * {@code jsonProperty} values covering the full stored-as-supplied space: flat names (e.g.
     * {@code firstName}), dot-paths (e.g. {@code address.city}), and odd/unicode strings. The only
     * contract on {@code jsonProperty} at create time is {@code @NotBlank}, so every generated value
     * is non-blank.
     */
    @Provide
    Arbitrary<String> jsonProperties() {
        Arbitrary<String> flat =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(24);

        Arbitrary<String> dotPaths = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(12)
                .list()
                .ofMinSize(2)
                .ofMaxSize(4)
                .map(segments -> String.join(".", segments));

        // Odd / unicode strings: letters, digits, punctuation and non-ASCII code points. Filtered to
        // be non-blank so they satisfy the create @NotBlank contract while still exercising verbatim
        // storage of unusual values.
        Arbitrary<String> odd = Arbitraries.strings()
                .withCharRange('\u00A1', '\u024F')   // Latin-1 supplement + extended
                .withCharRange('\u0370', '\u03FF')   // Greek
                .withCharRange('!', '~')             // printable ASCII incl. punctuation/spaces-adjacent
                .ofMinLength(1)
                .ofMaxLength(30)
                .filter(s -> !s.isBlank());

        return Arbitraries.oneOf(flat, dotPaths, odd);
    }

    /**
     * Valid kebab-case placeholders matching {@code ^[a-z0-9]+(-[a-z0-9]+)*$}: one or more
     * lowercase-alphanumeric segments joined by single hyphens.
     */
    @Provide
    Arbitrary<String> kebabPlaceholders() {
        Arbitrary<String> segment =
                Arbitraries.strings().withCharRange('a', 'z').numeric().ofMinLength(1).ofMaxLength(8);
        return segment.list().ofMinSize(1).ofMaxSize(5).map(segments -> String.join("-", segments));
    }

    // ------------------------------------------------------------------------
    // In-memory fake repository (Mockito mock backed by a Map)
    // ------------------------------------------------------------------------

    /**
     * Builds a repository fake whose {@code save} assigns a {@code UUID} id and {@code createdAt} /
     * {@code updatedAt} timestamps on first persist (mimicking the entity's {@code @PrePersist}
     * callback), stores the row in a backing map, and whose {@code findById} /
     * {@code existsByEntityTypeAndPlaceholder} read from that map.
     */
    private static EntityPropertyMappingRepository newFakeRepository() {
        EntityPropertyMappingRepository repository = mock(EntityPropertyMappingRepository.class);
        Map<UUID, EntityPropertyMapping> store = new HashMap<>();

        when(repository.save(any(EntityPropertyMapping.class))).thenAnswer(invocation -> {
            EntityPropertyMapping mapping = invocation.getArgument(0);
            if (mapping.getId() == null) {
                mapping.setId(UUID.randomUUID());
            }
            if (mapping.getCreatedAt() == null) {
                Instant now = Instant.now();
                mapping.setCreatedAt(now);
                mapping.setUpdatedAt(now);
            } else {
                mapping.setUpdatedAt(Instant.now());
            }
            store.put(mapping.getId(), mapping);
            return mapping;
        });

        when(repository.findById(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return Optional.ofNullable(store.get(id));
        });

        when(repository.existsByEntityTypeAndPlaceholder(any(), any())).thenAnswer(invocation -> {
            EntityType type = invocation.getArgument(0);
            String placeholder = invocation.getArgument(1);
            return store.values().stream()
                    .anyMatch(m -> m.getEntityType() == type && m.getPlaceholder().equals(placeholder));
        });

        return repository;
    }
}
