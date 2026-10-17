package com.storagemanager.communications.property;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storagemanager.communications.model.CommunicationTemplate;
import com.storagemanager.communications.model.CommunicationTemplateResponse;
import com.storagemanager.communications.model.CreateCommunicationTemplateRequest;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.UpdateCommunicationTemplateRequest;
import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;
import com.storagemanager.communications.repository.CommunicationTemplateRepository;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;
import com.storagemanager.communications.service.AuthorizationService;
import com.storagemanager.communications.service.CommunicationService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 18 — entityType persistence and response carry-through</b>.
 *
 * <p><b>Feature: communication-api, Property 18: entityType persistence and response
 * carry-through</b> — for a create or update that resolves to an effective {@code entityType} (null
 * or a defined value), the persisted template's {@code entityType} equals that effective value and
 * {@code CommunicationTemplateResponse.from} carries the same {@code entityType} through to the
 * response, preserving null when no entity type is associated.
 *
 * <p><b>Validates: Requirements 1.12, 6.14, 6.15, 7.3</b>
 */
class CommunicationEntityTypeCarryThroughPropertyTest {

    private static final UUID ORG = UUID.randomUUID();

    @Property(tries = 200)
    void createPersistsAndResponseCarriesEntityType(
            @ForAll("maybeEntityType") EntityType entityType) {

        TestService service = service();
        // Content with no placeholders so validation never interferes regardless of entityType.
        CreateCommunicationTemplateRequest request = new CreateCommunicationTemplateRequest(
                ORG, null, "tmpl-" + UUID.randomUUID().toString().substring(0, 8),
                "plain content", null, entityType);

        CommunicationTemplate persisted = service.create(request);
        assertThat(persisted.getEntityType())
                .as("persisted entityType equals the request value")
                .isEqualTo(entityType);

        CommunicationTemplateResponse response = CommunicationTemplateResponse.from(persisted);
        assertThat(response.entityType())
                .as("response carries the persisted entityType through, preserving null")
                .isEqualTo(entityType);
    }

    @Property(tries = 200)
    void updateAppliesNonNullAndLeavesNullUnchanged(
            @ForAll("maybeEntityType") EntityType stored,
            @ForAll("maybeEntityType") EntityType requested) {

        TestService service = service();
        CommunicationTemplate template = new CommunicationTemplate();
        template.setId(UUID.randomUUID());
        template.setOrganizationId(ORG);
        template.setTemplateName("seed");
        template.setEntityType(stored);
        when(service.templateRepositoryStub().findById(template.getId()))
                .thenReturn(java.util.Optional.of(template));

        // No content change / no placeholders → validation passes for any effective entityType.
        UpdateCommunicationTemplateRequest request =
                new UpdateCommunicationTemplateRequest(null, null, null, requested);

        CommunicationTemplate updated = service.update(template.getId(), request);

        // A non-null request value applies; a null request value leaves the stored value unchanged.
        EntityType expected = requested != null ? requested : stored;
        assertThat(updated.getEntityType())
                .as("non-null entityType applies; null leaves it unchanged")
                .isEqualTo(expected);
        assertThat(CommunicationTemplateResponse.from(updated).entityType())
                .as("response carries the effective entityType")
                .isEqualTo(expected);
    }

    // ------------------------------------------------------------------

    private static TestService service() {
        CommunicationTemplateRepository templateRepo = mock(CommunicationTemplateRepository.class);
        EntityPropertyMappingRepository mappingRepo = mock(EntityPropertyMappingRepository.class);
        AuthorizationService authz = mock(AuthorizationService.class);

        UserIdentity admin = new UserIdentity("admin-sub", UserRole.ADMIN, ORG);
        lenient().when(authz.getCurrentUserIdentity()).thenReturn(admin);
        lenient().when(authz.canAccessFacility(any())).thenReturn(true);
        lenient().when(templateRepo.existsByOrganizationIdAndFacilityIdIsNullAndTemplateName(any(), any()))
                .thenReturn(false);
        lenient().when(templateRepo.existsByOrganizationIdAndFacilityIdAndTemplateName(any(), any(), any()))
                .thenReturn(false);
        lenient().when(templateRepo.save(any(CommunicationTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // Any entityType resolves to an empty valid-placeholder set; content has no placeholders.
        lenient().when(mappingRepo.findPlaceholderByEntityType(any())).thenReturn(List.of());

        return new TestService(templateRepo, mappingRepo, authz);
    }

    private static final class TestService extends CommunicationService {
        private final CommunicationTemplateRepository templateRepo;

        TestService(CommunicationTemplateRepository templateRepo,
                    EntityPropertyMappingRepository mappingRepo,
                    AuthorizationService authz) {
            super(templateRepo, mappingRepo, authz);
            this.templateRepo = templateRepo;
        }

        CommunicationTemplateRepository templateRepositoryStub() {
            return templateRepo;
        }
    }

    // ------------------------------------------------------------------

    /** Either the single defined entity type or null (no association). */
    @Provide
    Arbitrary<EntityType> maybeEntityType() {
        return Arbitraries.of(EntityType.LEAD, null);
    }
}
