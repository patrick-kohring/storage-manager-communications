package com.storagemanager.communications.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;

/**
 * Persistence integration test for {@link EntityPropertyMapping} exercising the real
 * {@code (entity_type, placeholder)} uniqueness constraint and the deliberate absence of any
 * uniqueness constraint on {@code json_property} — the database-level counterpart to the
 * service-level Property 1.
 *
 * <p><b>Feature: entity-api — persistence integration (database-level counterpart to Property 1).</b>
 * Two mappings that share the same {@code jsonProperty} under <em>distinct</em> placeholders both
 * persist (the schema permits a shared property, Requirements 1.9 / 12.2), while a second row with a
 * duplicate {@code (entity_type, placeholder)} pair is rejected by the
 * {@code uq_entity_prop_mapping_type_placeholder} unique constraint on flush (Requirement 12.4).
 *
 * <p><b>Validates: Requirements 1.9, 12.2, 12.4</b>
 *
 * <h2>Harness</h2>
 * A {@code @DataJpaTest} slice following the communications module convention: H2 in PostgreSQL
 * compatibility mode, {@code ddl-auto=create-drop} so Hibernate materializes the schema (including
 * the entity's {@code @UniqueConstraint}) directly from the mapped {@link EntityPropertyMapping}
 * entity, and Flyway disabled. The host entity and its repository are picked up by the application's
 * package-level {@code @EntityScan}/{@code @EnableJpaRepositories}. This entity's DDL needs no
 * {@code JSONB} domain or partial-index shim (only {@code UUID}/{@code VARCHAR}/{@code TIMESTAMP}
 * columns with two NOT NULL unique-constrained columns), so the datasource carries no INIT clause.
 *
 * <p>Each insert is driven through {@code saveAndFlush} so the SQL (and the database unique
 * constraint) is exercised immediately rather than deferred to an implicit end-of-transaction
 * flush.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:comm_entity_mapping_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // Hibernate materializes the schema (incl. the @UniqueConstraint) from the mapped entity.
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
})
class EntityPropertyMappingPersistenceIT {

    @Autowired
    private EntityPropertyMappingRepository repository;

    private static EntityPropertyMapping mapping(String jsonProperty, String placeholder) {
        EntityPropertyMapping mapping = new EntityPropertyMapping();
        mapping.setEntityType(EntityType.LEAD);
        mapping.setJsonProperty(jsonProperty);
        mapping.setPlaceholder(placeholder);
        return mapping;
    }

    // Req 1.9 / 12.2: the schema permits two mappings to share the same jsonProperty as long as
    // their placeholders differ — there is deliberately no unique constraint on json_property.
    @Test
    void sharedJsonPropertyUnderDistinctPlaceholdersBothPersist() {
        EntityPropertyMapping first = repository.saveAndFlush(mapping("firstName", "first-name"));
        EntityPropertyMapping second = repository.saveAndFlush(mapping("firstName", "given-name"));

        // Both rows exist with distinct, generated ids and the identical shared jsonProperty.
        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getJsonProperty()).isEqualTo("firstName");
        assertThat(second.getJsonProperty()).isEqualTo("firstName");
        assertThat(repository.count()).as("both shared-property mappings persist").isEqualTo(2L);
    }

    // Req 12.4: a second row with the same (entity_type, placeholder) pair is rejected by the
    // uq_entity_prop_mapping_type_placeholder database unique constraint on flush.
    @Test
    void duplicateEntityTypeAndPlaceholderPairIsRejectedOnFlush() {
        repository.saveAndFlush(mapping("firstName", "first-name"));

        // A second mapping with the SAME (LEAD, first-name) pair — even with a different
        // jsonProperty — collides on the composite unique constraint when flushed to the database.
        EntityPropertyMapping duplicate = mapping("givenName", "first-name");

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .as("duplicate (entity_type, placeholder) pair violates the DB unique constraint")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Req 12.4 (negative control): the same placeholder is accepted when it has not already been
    // used, confirming the rejection above is driven by the duplicate pair and not an unrelated
    // constraint. Distinct placeholders under the same entity type persist without conflict.
    @Test
    void distinctPlaceholdersUnderSameEntityTypePersistWithoutConflict() {
        assertThatCode(() -> {
            repository.saveAndFlush(mapping("firstName", "first-name"));
            repository.saveAndFlush(mapping("lastName", "last-name"));
        }).doesNotThrowAnyException();

        assertThat(repository.count()).isEqualTo(2L);
    }
}
