package com.storagemanager.communications.property;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storagemanager.communications.model.CommunicationTemplate;
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
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 16 — Placeholder validity under an entity type</b>.
 *
 * <p><b>Feature: communication-api, Property 16: Placeholder validity under an entity type</b> —
 * for a non-null effective {@code entityType}, a generated global {@code EntityPropertyMapping}
 * placeholder set for that type, and arbitrary {@code emailContent}/{@code smsContent} (including
 * null fields and arbitrary org/facility scope), validation passes if and only if every
 * {@code {{...}}} occurrence's trimmed inner name is both well-formed
 * ({@code ^[a-z0-9]+(-[a-z0-9]+)*$}) and present in that type's placeholder set; otherwise the
 * request is rejected with a {@code 400} ({@link IllegalArgumentException}) naming each distinct
 * offending inner name, and zero {@code {{...}}} occurrences always pass. The outcome depends only
 * on the content and {@code entityType}, independent of the template's org/facility, and holds
 * identically on the create and update write paths.
 *
 * <p><b>Validates: Requirements 3.11, 6.17, 11.1, 11.2, 11.3, 11.5, 11.6, 11.7, 11.9</b>
 *
 * <h2>Approach</h2>
 * The real {@link CommunicationService} is exercised against mocked collaborators: the
 * {@link CommunicationTemplateRepository} echoes the saved entity, the {@link AuthorizationService}
 * yields an ADMIN identity (so org/facility scoping never interferes with the placeholder check),
 * and the {@link EntityPropertyMappingRepository} returns a generated "valid" placeholder set from
 * {@code findPlaceholderByEntityType}. Each generated case is driven through BOTH {@code create}
 * and {@code update} and the two outcomes are asserted identical.
 */
class CommunicationPlaceholderValidityPropertyTest {

    private static final UUID ORG = UUID.randomUUID();

    @Property(tries = 200)
    void placeholderValidityHoldsIdenticallyOnCreateAndUpdate(
            @ForAll("scenarios") Scenario scenario) {

        List<String> validList = new ArrayList<>(scenario.validPlaceholders());

        // --- create path ---
        TestService createService = serviceWith(validList);
        CreateCommunicationTemplateRequest createRequest = new CreateCommunicationTemplateRequest(
                ORG, null, "tmpl-" + UUID.randomUUID().toString().substring(0, 8),
                scenario.emailContent(), null, EntityType.LEAD);
        Throwable createError = catchThrowable(() -> createService.create(createRequest));

        // --- update path (same effective content/entityType via the stored template) ---
        TestService updateService = serviceWith(validList);
        CommunicationTemplate stored = seedTemplate(scenario.emailContent());
        when(updateService.templateRepositoryStub().findById(stored.getId()))
                .thenReturn(java.util.Optional.of(stored));
        UpdateCommunicationTemplateRequest updateRequest = new UpdateCommunicationTemplateRequest(
                null, scenario.emailContent(), null, EntityType.LEAD);
        Throwable updateError =
                catchThrowable(() -> updateService.update(stored.getId(), updateRequest));

        // --- expectation: pass iff every offender set is empty ---
        boolean expectRejected = !scenario.expectedOffenders().isEmpty();

        if (expectRejected) {
            assertThat(createError)
                    .as("create must reject invalid/unknown placeholders")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(updateError)
                    .as("update must reject invalid/unknown placeholders")
                    .isInstanceOf(IllegalArgumentException.class);
            // Each distinct offender is named in both messages.
            for (String offender : scenario.expectedOffenders()) {
                assertThat(createError.getMessage())
                        .as("create message names offender '%s'", offender)
                        .contains(offender);
                assertThat(updateError.getMessage())
                        .as("update message names offender '%s'", offender)
                        .contains(offender);
            }
        } else {
            assertThat(createError).as("create must pass when all placeholders are valid").isNull();
            assertThat(updateError).as("update must pass when all placeholders are valid").isNull();
        }
    }

    // ------------------------------------------------------------------
    // Service wiring (mocked collaborators)
    // ------------------------------------------------------------------

    /** Builds a {@link TestService} exposing the stubbed template repository for the update path. */
    private static TestService serviceWith(List<String> validPlaceholders) {
        CommunicationTemplateRepository templateRepo = mock(CommunicationTemplateRepository.class);
        EntityPropertyMappingRepository mappingRepo = mock(EntityPropertyMappingRepository.class);
        AuthorizationService authz = mock(AuthorizationService.class);

        // ADMIN identity so org/facility scoping is a no-op; placeholder validation is isolated.
        UserIdentity admin = new UserIdentity("admin-sub", UserRole.ADMIN, ORG);
        lenient().when(authz.getCurrentUserIdentity()).thenReturn(admin);
        lenient().when(authz.canAccessFacility(any())).thenReturn(true);

        // Uniqueness pre-check always passes (distinct names).
        lenient().when(templateRepo.existsByOrganizationIdAndFacilityIdIsNullAndTemplateName(any(), any()))
                .thenReturn(false);
        lenient().when(templateRepo.existsByOrganizationIdAndFacilityIdAndTemplateName(any(), any(), any()))
                .thenReturn(false);
        // save echoes the entity back.
        lenient().when(templateRepo.save(any(CommunicationTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        lenient().when(mappingRepo.findPlaceholderByEntityType(eq(EntityType.LEAD)))
                .thenReturn(validPlaceholders);

        return new TestService(templateRepo, mappingRepo, authz);
    }

    /** Lightweight subclass-free holder so the test can stub findById on the update path. */
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

    private static CommunicationTemplate seedTemplate(String emailContent) {
        CommunicationTemplate t = new CommunicationTemplate();
        t.setId(UUID.randomUUID());
        t.setOrganizationId(ORG);
        t.setTemplateName("seed");
        t.setEmailContent(emailContent);
        t.setEntityType(EntityType.LEAD);
        return t;
    }

    // ------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------

    /**
     * A scenario carries a valid-placeholder set, email content embedding a mix of valid, unknown,
     * and malformed {@code {{...}}} occurrences (plus whitespace padding), and the expected set of
     * distinct offenders (empty ⇒ must pass).
     */
    record Scenario(Set<String> validPlaceholders, String emailContent, Set<String> expectedOffenders) {}

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<String>> validNames = kebab().list().ofMinSize(1).ofMaxSize(5);
        // 0..4 tokens, each tagged as valid / unknown / malformed.
        Arbitrary<List<Token>> tokens = tokens().list().ofMinSize(0).ofMaxSize(4);
        return Combinators.combine(validNames, tokens).as((valid, toks) -> buildScenario(valid, toks));
    }

    private static Scenario buildScenario(List<String> validNames, List<Token> tokens) {
        java.util.LinkedHashSet<String> valid = new java.util.LinkedHashSet<>(validNames);
        StringBuilder content = new StringBuilder("Hello ");
        // The service validates in two phases against the effective content: it rejects malformed
        // inner names FIRST (Req 11.9) and only checks mapping membership (Req 11.3) when every
        // inner name is well-formed. Mirror that precedence here: when any malformed token is
        // present, only the malformed names are expected in the 400 message; otherwise the unknown
        // names are.
        java.util.LinkedHashSet<String> malformedOffenders = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> unknownOffenders = new java.util.LinkedHashSet<>();

        int validIdx = 0;
        for (Token token : tokens) {
            String inner;
            switch (token.kind()) {
                case VALID -> {
                    // Reuse a known-valid name (cycle through the valid set).
                    inner = validNames.get(validIdx % validNames.size());
                    validIdx++;
                }
                case UNKNOWN -> {
                    inner = "zzz-" + token.seed(); // kebab-valid but not in the valid set
                    if (!valid.contains(inner)) {
                        unknownOffenders.add(inner);
                    }
                }
                default -> {
                    inner = token.malformed(); // not kebab-valid or empty
                    malformedOffenders.add(inner.strip());
                }
            }
            // Pad with surrounding whitespace inside the braces to exercise trimming.
            content.append("{{ ").append(inner).append(" }}").append(' ');
        }
        java.util.LinkedHashSet<String> expected =
                malformedOffenders.isEmpty() ? unknownOffenders : malformedOffenders;
        return new Scenario(valid, content.toString(), expected);
    }

    enum Kind { VALID, UNKNOWN, MALFORMED }

    record Token(Kind kind, int seed, String malformed) {}

    @Provide
    Arbitrary<Token> tokens() {
        Arbitrary<Token> valid = Arbitraries.integers().between(0, 9).map(i -> new Token(Kind.VALID, i, null));
        Arbitrary<Token> unknown = Arbitraries.integers().between(0, 9).map(i -> new Token(Kind.UNKNOWN, i, null));
        Arbitrary<Token> malformed = Arbitraries.of(
                "Not-Kebab", "under_score", "has space", "UPPER", "trailing-", "-leading", "a--b")
                .map(m -> new Token(Kind.MALFORMED, 0, m));
        return Arbitraries.oneOf(valid, unknown, malformed);
    }

    /** Kebab-valid inner names drawn from a small pool so VALID tokens reliably resolve. */
    @Provide
    Arbitrary<String> kebab() {
        return Arbitraries.of("first-name", "last-name", "email", "mobile-phone",
                "address-city", "facility-name", "a", "x1");
    }
}
