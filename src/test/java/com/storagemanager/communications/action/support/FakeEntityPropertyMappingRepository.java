package com.storagemanager.communications.action.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;

/**
 * Shared in-memory fake {@link EntityPropertyMappingRepository} for the {@code ActionProcessor}
 * property-test harness (lead-communication-action spec).
 *
 * <p>Built as a Mockito mock backed by a {@link ConcurrentHashMap}, matching the module's existing
 * Mockito-backed fake-repository pattern. The {@code ActionProcessor} builds its {@code LEAD}
 * {@code placeholder -> jsonProperty} map by calling
 * {@link EntityPropertyMappingRepository#findByEntityTypeOrderByCreatedAtDesc(EntityType, Pageable)}
 * with {@link Pageable#unpaged()} and reading {@code getContent()} (Req 6.1, 6.2), so that finder
 * is the behavior this fake supplies. {@code save} is also stubbed so generators can seed rows
 * through the normal entity path (assigning an id on first persist, mimicking {@code @PrePersist}).
 *
 * <p>Usage from a sibling test:
 * <pre>{@code
 * FakeEntityPropertyMappingRepository mappings = new FakeEntityPropertyMappingRepository();
 * mappings.seedLeadMapping("first-name", "firstName");
 * EntityPropertyMappingRepository repo = mappings.mock();
 * }</pre>
 */
public final class FakeEntityPropertyMappingRepository {

    private final EntityPropertyMappingRepository mock;
    private final ConcurrentHashMap<UUID, EntityPropertyMapping> store = new ConcurrentHashMap<>();

    public FakeEntityPropertyMappingRepository() {
        this.mock = Mockito.mock(EntityPropertyMappingRepository.class);
        wireSave();
        wireEntityTypeFinder();
    }

    /** @return the Mockito-backed {@link EntityPropertyMappingRepository} to inject into the processor. */
    public EntityPropertyMappingRepository mock() {
        return mock;
    }

    /**
     * Seeds a single {@code LEAD} mapping row.
     *
     * @param placeholder  the kebab-case placeholder inner name
     * @param jsonProperty the lead json property it resolves to
     * @return the stored mapping (with an assigned id)
     */
    public EntityPropertyMapping seedLeadMapping(String placeholder, String jsonProperty) {
        EntityPropertyMapping mapping = new EntityPropertyMapping();
        mapping.setEntityType(EntityType.LEAD);
        mapping.setPlaceholder(placeholder);
        mapping.setJsonProperty(jsonProperty);
        return mock.save(mapping);
    }

    /**
     * Seeds every entry of a {@code placeholder -> jsonProperty} map as {@code LEAD} mappings.
     */
    public void seedLeadMappings(Map<String, String> placeholderToJsonProperty) {
        placeholderToJsonProperty.forEach(this::seedLeadMapping);
    }

    /** Removes all seeded mappings. */
    public void clear() {
        store.clear();
    }

    private void wireSave() {
        Mockito.when(mock.save(Mockito.any(EntityPropertyMapping.class))).thenAnswer(invocation -> {
            EntityPropertyMapping mapping = invocation.getArgument(0);
            if (mapping.getId() == null) {
                mapping.setId(UUID.randomUUID());
            }
            store.put(mapping.getId(), mapping);
            return mapping;
        });
    }

    private void wireEntityTypeFinder() {
        Mockito.when(mock.findByEntityTypeOrderByCreatedAtDesc(Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> {
                    EntityType entityType = invocation.getArgument(0);
                    List<EntityPropertyMapping> matches = new ArrayList<>();
                    for (EntityPropertyMapping mapping : store.values()) {
                        if (mapping.getEntityType() == entityType) {
                            matches.add(mapping);
                        }
                    }
                    return new PageImpl<>(matches);
                });
    }
}
