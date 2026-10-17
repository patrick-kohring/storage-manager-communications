package com.storagemanager.communications.property;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagemanager.communications.dto.CreateEntityPropertyMappingRequest;
import com.storagemanager.communications.filter.CustomJwtAuthenticationConverter;
import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.service.EntityPropertyMappingService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.spring.JqwikSpringSupport;

/**
 * jqwik property test proving the design's
 * <b>Correctness Property 2 — Kebab-case placeholder enforcement</b> for the {@code entity-api}
 * feature, driven end-to-end through the real security filter chain and
 * {@code EntityPropertyMappingController} create endpoint via {@link MockMvc}.
 *
 * <p><b>Feature: entity-api, Property 2: Kebab-case placeholder enforcement</b> — for an authenticated
 * ADMIN caller, a candidate {@code placeholder} on {@code POST /api/v1/entity-property-mappings} is
 * accepted (format-wise) <em>iff</em> it matches the Kebab_Case_Pattern
 * {@code ^[a-z0-9]+(-[a-z0-9]+)*$}:
 * <ul>
 *   <li>a value matching the pattern passes Bean Validation and reaches
 *       {@link EntityPropertyMappingService#create} (Requirements 5.4, 11.1);</li>
 *   <li>a value <em>not</em> matching the pattern (uppercase, underscore, leading/trailing/double
 *       hyphen, braces, spaces, empty) is rejected with HTTP {@code 400} whose error body names the
 *       {@code placeholder} field (a {@code MethodArgumentNotValidException}), and the service is
 *       <em>never</em> invoked (Requirements 5.4, 8.5, 11.3);</li>
 *   <li>every placeholder that the service does persist matches the pattern and therefore never
 *       contains the {@code {{} or {@code }}} template delimiters (Requirement 11.2) — the inner
 *       name only.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 5.4, 8.5, 11.1, 11.2, 11.3</b>
 *
 * <h2>Harness</h2>
 * The feature boots the full application via {@link SpringBootTest} + {@link AutoConfigureMockMvc}
 * so the real {@code SecurityConfig} filter chain, {@code CustomJwtAuthenticationConverter},
 * {@code AuthorizationService} ({@code @authz.isAdmin()}), {@code EntityPropertyMappingController},
 * {@code GlobalExceptionHandler}, and Bean Validation all participate. The
 * {@link EntityPropertyMappingService} is a {@link MockBean} so a format-valid request "succeeds"
 * (the pre-persist uniqueness/DB concerns are out of scope for this property) and so the property
 * can assert the service is <em>never</em> reached when validation rejects a bad placeholder. A
 * {@link MockBean} {@link JwtDecoder} keeps context start from attempting a JWKS network fetch.
 *
 * <p>The ADMIN caller is produced by running a {@link Jwt} carrying the {@code custom:role=ADMIN}
 * claim through the real {@link CustomJwtAuthenticationConverter}, yielding a
 * {@link JwtAuthenticationToken} whose details are a {@code UserIdentity} with role {@code ADMIN} —
 * exactly what {@code @authz.isAdmin()} reads. This exercises the production authorization path
 * rather than a hand-rolled authority. {@code @JqwikSpringSupport} bridges jqwik's lifecycle to the
 * Spring {@code TestContext} so the autowired MockMvc/service/converter beans are available inside
 * the {@code @Property} methods. Tries are set to 200 (above the design minimum of 100).
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // H2 in PostgreSQL mode WITH the JSONB domain alias so the messaging starter's enriched
        // inbox/outbox JSONB columns materialize under the booted context (same posture as the
        // existing InboundEventControllerMockMvcTest).
        "spring.datasource.url=jdbc:h2:mem:comm_kebab_prop;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "storagemanager.messaging.inbox.enabled=true",
        "storagemanager.messaging.outbox.enabled=true",
        "storagemanager.messaging.enrichment.enabled=true",
        "storagemanager.messaging.scope-fields[0]=organization_id",
        "storagemanager.messaging.scope-fields[1]=facility_id",
        "storagemanager.messaging.outbox.poll-interval-ms=3600000"
})
class EntityMappingKebabPropertyTest {

    private static final String CREATE_PATH = "/api/v1/entity-property-mappings";
    private static final String ROLE_CLAIM = "custom:role";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomJwtAuthenticationConverter jwtAuthenticationConverter;

    /** Mocked so a format-valid request "succeeds" and so invalid requests can be proven to never reach it. */
    @MockBean
    private EntityPropertyMappingService service;

    /** Mocked so the resource-server decoder does not attempt a JWKS network fetch at context start. */
    @MockBean
    private JwtDecoder jwtDecoder;

    // ─────────────────────────────────────────────────────────────────────────────
    // Property 2a — a kebab-case placeholder is accepted (format-wise): validation passes
    //               and the create reaches the service with the exact placeholder, which
    //               always matches the pattern and never contains {{ or }}.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * For any generated placeholder that matches {@code ^[a-z0-9]+(-[a-z0-9]+)*$}, the create
     * request passes Bean Validation, reaches {@link EntityPropertyMappingService#create} exactly
     * once with the supplied placeholder, and that persisted placeholder matches the pattern and
     * contains neither {@code {{} nor {@code }}} (Requirements 5.4, 11.1, 11.2).
     *
     * <p><b>Validates: Requirements 5.4, 11.1, 11.2</b>
     */
    @Property(tries = 200)
    void kebabCasePlaceholderIsAcceptedAndReachesServiceVerbatim(
            @ForAll("validPlaceholders") String placeholder,
            @ForAll("jsonProperties") String jsonProperty) throws Exception {

        reset(service);
        // A format-valid request "succeeds" — stub the service to echo a persisted entity.
        when(service.create(any(CreateEntityPropertyMappingRequest.class)))
                .thenAnswer(invocation -> {
                    CreateEntityPropertyMappingRequest req = invocation.getArgument(0);
                    return persisted(req.entityType(), req.jsonProperty(), req.placeholder());
                });

        String body = objectMapper.writeValueAsString(
                requestBody(EntityType.LEAD, jsonProperty, placeholder));

        MvcResult result = mockMvc.perform(post(CREATE_PATH)
                        .with(authentication(adminAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        // Accepted format-wise: not a 400 validation rejection (expected 201 Created).
        assertThat(result.getResponse().getStatus())
                .as("a kebab-case placeholder must not be rejected by validation")
                .isEqualTo(201);

        // The service was reached exactly once, with the exact placeholder supplied.
        var captor = org.mockito.ArgumentCaptor.forClass(CreateEntityPropertyMappingRequest.class);
        verify(service, times(1)).create(captor.capture());
        String persistedPlaceholder = captor.getValue().placeholder();

        assertThat(persistedPlaceholder).isEqualTo(placeholder);
        assertThat(persistedPlaceholder)
                .as("every persisted placeholder matches the kebab-case pattern")
                .matches("^[a-z0-9]+(-[a-z0-9]+)*$");
        assertThat(persistedPlaceholder)
                .as("a persisted placeholder is the inner name only — no template delimiters")
                .doesNotContain("{{")
                .doesNotContain("}}");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Property 2b — a NON-kebab placeholder is rejected 400 naming `placeholder`, and the
    //               service is never invoked.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * For any generated placeholder that does <em>not</em> match the Kebab_Case_Pattern (uppercase,
     * underscore, leading/trailing/double hyphen, braces, spaces, empty), the create endpoint
     * returns HTTP {@code 400} whose body names the {@code placeholder} field, and
     * {@link EntityPropertyMappingService#create} is never invoked — validation fences the bad
     * value out before any business logic runs (Requirements 5.4, 8.5, 11.3).
     *
     * <p><b>Validates: Requirements 5.4, 8.5, 11.3</b>
     */
    @Property(tries = 200)
    void nonKebabPlaceholderIsRejected400NamingPlaceholderAndNeverReachesService(
            @ForAll("invalidPlaceholders") String placeholder,
            @ForAll("jsonProperties") String jsonProperty) throws Exception {

        // Precondition guard: the generated value really is NOT kebab-case.
        if (placeholder.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
            return;
        }

        reset(service);

        String body = objectMapper.writeValueAsString(
                requestBody(EntityType.LEAD, jsonProperty, placeholder));

        MvcResult result = mockMvc.perform(post(CREATE_PATH)
                        .with(authentication(adminAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a non-kebab placeholder must be rejected with 400 for '%s'", placeholder)
                .isEqualTo(400);

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody)
                .as("the 400 error body must name the failing `placeholder` field for '%s'", placeholder)
                .contains("placeholder");

        // Validation fences the value out before business logic — the service is never reached.
        verify(service, never()).create(any());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────────────────────

    /** Builds a JSON body map so an empty-string placeholder is still emitted as a field. */
    private Map<String, Object> requestBody(EntityType entityType, String jsonProperty, String placeholder) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entityType", entityType.name());
        body.put("jsonProperty", jsonProperty);
        body.put("placeholder", placeholder);
        return body;
    }

    private EntityPropertyMapping persisted(EntityType entityType, String jsonProperty, String placeholder) {
        EntityPropertyMapping m = new EntityPropertyMapping();
        m.setId(UUID.randomUUID());
        m.setEntityType(entityType);
        m.setJsonProperty(jsonProperty);
        m.setPlaceholder(placeholder);
        Instant now = Instant.now();
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return m;
    }

    /**
     * Produces an authenticated ADMIN caller by running a {@code custom:role=ADMIN} JWT through the
     * real {@link CustomJwtAuthenticationConverter}, so the resulting token's details are a
     * {@code UserIdentity} with role {@code ADMIN} — exactly what {@code @authz.isAdmin()} reads.
     */
    private JwtAuthenticationToken adminAuthentication() {
        Jwt jwt = Jwt.withTokenValue("admin-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim(ROLE_CLAIM, "ADMIN")
                .claim("organizationId", UUID.randomUUID().toString())
                .build();
        return (JwtAuthenticationToken) jwtAuthenticationConverter.convert(jwt);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Generators
    // ─────────────────────────────────────────────────────────────────────────────

    /** Non-blank values that must be stored as-is (no interpretation). */
    @Provide
    Arbitrary<String> jsonProperties() {
        return Arbitraries.of(
                "firstName", "lastName", "email", "address.city", "phone",
                "contact.primary.email", "a", "weird\u00e9name");
    }

    /**
     * Valid kebab-case placeholders: one or more lowercase-alphanumeric segments joined by single
     * hyphens, built to always satisfy {@code ^[a-z0-9]+(-[a-z0-9]+)*$}.
     */
    @Provide
    Arbitrary<String> validPlaceholders() {
        Arbitrary<String> segment = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789")
                .ofMinLength(1)
                .ofMaxLength(8);
        return segment.list().ofMinSize(1).ofMaxSize(4)
                .map(segments -> String.join("-", segments));
    }

    /**
     * Invalid placeholder candidates spanning each rejection class called out by the spec:
     * uppercase, underscore, leading/trailing/double hyphen, braces, spaces, and empty — plus a
     * generative stream mixing illegal characters. A {@code @Property} precondition guard drops any
     * value that happens to be valid kebab-case so only true negatives are asserted.
     */
    @Provide
    Arbitrary<String> invalidPlaceholders() {
        Arbitrary<String> curated = Arbitraries.of(
                "",                 // empty
                "First-Name",       // uppercase
                "FIRSTNAME",        // all uppercase
                "first_name",       // underscore
                "-leading",         // leading hyphen
                "trailing-",        // trailing hyphen
                "double--hyphen",   // double hyphen
                "{{first-name}}",   // braces (template delimiters)
                "first name",       // space
                " first-name",      // leading space
                "first-name ",      // trailing space
                "first.name",       // dot
                "first@name",       // special char
                "camelCase",        // uppercase interior
                "-",                // lone hyphen
                "--");              // only hyphens
        // Generative illegal strings: freely mix chars outside the kebab alphabet.
        Arbitrary<String> generative = Arbitraries.strings()
                .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZ_ .{}@-abc123")
                .ofMinLength(0)
                .ofMaxLength(12);
        return Arbitraries.oneOf(curated, generative);
    }
}
