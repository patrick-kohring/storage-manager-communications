package com.storagemanager.communications.action.support;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import com.storagemanager.communications.model.CommunicationTemplate;
import com.storagemanager.communications.repository.CommunicationTemplateRepository;

/**
 * Shared in-memory fake {@link CommunicationTemplateRepository} for the {@code ActionProcessor}
 * property-test harness (lead-communication-action spec).
 *
 * <p>Built as a Mockito mock backed by a {@link ConcurrentHashMap}, matching the Mockito-backed
 * fake-repository pattern already used in this module (for example
 * {@code EntityMappingRoundTripPropertyTest}). Only the behavior the {@code ActionProcessor} relies
 * on is stubbed:
 * <ul>
 *   <li>the two scope finders used by template resolution (Req 3.2, 3.3) —
 *       {@link CommunicationTemplateRepository#findByOrganizationIdAndFacilityIdAndTemplateName} and
 *       {@link CommunicationTemplateRepository#findByOrganizationIdAndFacilityIdIsNullAndTemplateName};</li>
 *   <li>{@code save} / {@code saveAndFlush}, which assign a {@code UUID} id on first persist
 *       (mimicking {@code @PrePersist}) and store the row by id;</li>
 *   <li>{@code findById}.</li>
 * </ul>
 *
 * <p>Usage from a sibling test:
 * <pre>{@code
 * FakeCommunicationTemplateRepository templates = new FakeCommunicationTemplateRepository();
 * templates.seedFacilityTemplate(orgId, facilityId, "lead-welcome", "Hi {{first-name}}", null);
 * CommunicationTemplateRepository repo = templates.mock();
 * }</pre>
 *
 * <p>The underlying Mockito mock is reusable across every ActionProcessor property test (7.2, 7.3,
 * 7.5, 7.7, 7.8, 7.10, 7.11, 7.13, 7.14, 7.15): seed the templates you need, then pass {@link #mock()}
 * as the repository collaborator.
 */
public final class FakeCommunicationTemplateRepository {

    private final CommunicationTemplateRepository mock;
    private final ConcurrentHashMap<UUID, CommunicationTemplate> store = new ConcurrentHashMap<>();

    public FakeCommunicationTemplateRepository() {
        this.mock = Mockito.mock(CommunicationTemplateRepository.class);
        wireSave();
        wireFindById();
        wireScopeFinders();
    }

    /** @return the Mockito-backed {@link CommunicationTemplateRepository} to inject into the processor. */
    public CommunicationTemplateRepository mock() {
        return mock;
    }

    /**
     * Seeds a facility-scoped template (non-null {@code facilityId}).
     *
     * @return the stored template (with an assigned id)
     */
    public CommunicationTemplate seedFacilityTemplate(UUID organizationId, UUID facilityId,
                                                      String templateName, String emailContent,
                                                      String smsContent) {
        Objects.requireNonNull(facilityId, "facilityId must not be null for a facility template");
        return seed(organizationId, facilityId, templateName, emailContent, smsContent);
    }

    /**
     * Seeds an org-scoped template (null {@code facilityId}).
     *
     * @return the stored template (with an assigned id)
     */
    public CommunicationTemplate seedOrgTemplate(UUID organizationId, String templateName,
                                                 String emailContent, String smsContent) {
        return seed(organizationId, null, templateName, emailContent, smsContent);
    }

    /**
     * Seeds a template at the given scope. Lower-level entry point for generators that need to vary
     * the facility (null or non-null) directly.
     *
     * @return the stored template (with an assigned id)
     */
    public CommunicationTemplate seed(UUID organizationId, UUID facilityId, String templateName,
                                      String emailContent, String smsContent) {
        CommunicationTemplate template = new CommunicationTemplate();
        template.setOrganizationId(organizationId);
        template.setFacilityId(facilityId);
        template.setTemplateName(templateName);
        template.setEmailContent(emailContent);
        template.setSmsContent(smsContent);
        return mock.save(template);
    }

    /** Removes all seeded templates. */
    public void clear() {
        store.clear();
    }

    private void wireSave() {
        Answer<CommunicationTemplate> saveAnswer = invocation -> {
            CommunicationTemplate template = invocation.getArgument(0);
            if (template.getId() == null) {
                template.setId(UUID.randomUUID());
            }
            store.put(template.getId(), template);
            return template;
        };
        Mockito.when(mock.save(Mockito.any(CommunicationTemplate.class))).thenAnswer(saveAnswer);
        Mockito.when(mock.saveAndFlush(Mockito.any(CommunicationTemplate.class))).thenAnswer(saveAnswer);
    }

    private void wireFindById() {
        Mockito.when(mock.findById(Mockito.any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return Optional.ofNullable(store.get(id));
        });
    }

    private void wireScopeFinders() {
        // Facility-level finder (Req 3.2): match (organizationId, facilityId, templateName) exactly.
        Mockito.when(mock.findByOrganizationIdAndFacilityIdAndTemplateName(
                        Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> {
                    UUID organizationId = invocation.getArgument(0);
                    UUID facilityId = invocation.getArgument(1);
                    String templateName = invocation.getArgument(2);
                    return store.values().stream()
                            .filter(t -> Objects.equals(t.getOrganizationId(), organizationId))
                            .filter(t -> Objects.equals(t.getFacilityId(), facilityId))
                            .filter(t -> Objects.equals(t.getTemplateName(), templateName))
                            .findFirst();
                });

        // Org-level finder (Req 3.3): match (organizationId, facilityId IS NULL, templateName).
        Mockito.when(mock.findByOrganizationIdAndFacilityIdIsNullAndTemplateName(
                        Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> {
                    UUID organizationId = invocation.getArgument(0);
                    String templateName = invocation.getArgument(1);
                    return store.values().stream()
                            .filter(t -> Objects.equals(t.getOrganizationId(), organizationId))
                            .filter(t -> t.getFacilityId() == null)
                            .filter(t -> Objects.equals(t.getTemplateName(), templateName))
                            .findFirst();
                });
    }
}
