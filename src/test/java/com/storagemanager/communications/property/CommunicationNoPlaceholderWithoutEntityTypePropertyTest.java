package com.storagemanager.communications.property;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storagemanager.communications.model.CommunicationTemplate;
import com.storagemanager.communications.model.CreateCommunicationTemplateRequest;
import com.storagemanager.communications.model.UpdateCommunicationTemplateRequest;
import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;
import com.storagemanager.communications.repository.CommunicationTemplateRepository;
import com.storagemanager.communications.repository.EntityPropertyMappingRepository;
import com.storagemanager.communications.service.AuthorizationService;
import com.storagemanager.communications.service.CommunicationService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 17 — No placeholders without an entity type</b>.
 *
 * <p><b>Feature: communication-api, Property 17: No placeholders without an entity type</b> — for a
 * null effective {@code entityType} and arbitrary {@code emailContent}/{@code smsContent},
 * validation is rejected with a {@code 400} ({@link IllegalArgumentException}, "placeholders not
 * permitted") if and only if at least one {@code {{...}}} occurrence is present across the two
 * content fields (a null field contributing none); content with zero occurrences passes. This holds
 * identically on the create and update write paths.
 *
 * <p><b>Validates: Requirements 11.4, 11.6, 3.11, 6.17</b>
 */
class CommunicationNoPlaceholderWithoutEntityTypePropertyTest {

    private static final UUID ORG = UUID.randomUUID();

    @Property(tries = 200)
    void nullEntityTypeRejectsAnyPlaceholderOnCreateAndUpdate(
            @ForAll("contents") Content content) {

        boolean expectRejected = content.hasPlaceholder();

        // --- create (entityType null) ---
        TestService createService = service();
        CreateCommunicationTemplateRequest createRequest = new CreateCommunicationTemplateRequest(
                ORG, null, "tmpl-" + UUID.randomUUID().toString().substring(0, 8),
                content.email(), content.sms(), null);
        Throwable createError = catchThrowable(() -> createService.create(createRequest));

        // --- update (effective entityType null; stored template also null) ---
        TestService updateService = service();
        CommunicationTemplate stored = seedTemplate();
        when(updateService.templateRepositoryStub().findById(stored.getId()))
                .thenReturn(java.util.Optional.of(stored));
        UpdateCommunicationTemplateRequest updateRequest = new UpdateCommunicationTemplateRequest(
                null, content.email(), content.sms(), null);
        Throwable updateError =
                catchThrowable(() -> updateService.update(stored.getId(), updateRequest));

        if (expectRejected) {
            assertThat(createError)
                    .as("create with placeholder(s) and null entityType must be rejected")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(updateError)
                    .as("update with placeholder(s) and null entityType must be rejected")
                    .isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThat(createError).as("create with no placeholders must pass").isNull();
            assertThat(updateError).as("update with no placeholders must pass").isNull();
        }
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

    private static CommunicationTemplate seedTemplate() {
        CommunicationTemplate t = new CommunicationTemplate();
        t.setId(UUID.randomUUID());
        t.setOrganizationId(ORG);
        t.setTemplateName("seed");
        t.setEntityType(null);
        return t;
    }

    // ------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------

    record Content(String email, String sms, boolean hasPlaceholder) {}

    @Provide
    Arbitrary<Content> contents() {
        Arbitrary<String> emailFrag = fragment();
        Arbitrary<String> smsFrag = fragment();
        return Combinators.combine(emailFrag, smsFrag).as((email, sms) -> {
            String e = nullable(email);
            String s = nullable(sms);
            boolean has = (e != null && e.contains("{{")) || (s != null && s.contains("{{"));
            return new Content(e, s, has);
        });
    }

    /** A content fragment that may or may not embed a {{...}} occurrence (or be blank). */
    @Provide
    Arbitrary<String> fragment() {
        return Arbitraries.of(
                "plain text no braces",
                "",
                "hello {{first-name}}",
                "two {{a}} and {{b}}",
                "braces with space {{ first-name }}",
                "only prose here");
    }

    /** Map the sentinel empty string to null ~1/3 of the time to exercise null content. */
    private static String nullable(String value) {
        return "".equals(value) ? null : value;
    }
}
