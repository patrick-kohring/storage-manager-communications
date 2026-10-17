package com.storagemanager.communications.property;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;
import com.storagemanager.communications.service.EntityPropertyMappingService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.spring.JqwikSpringSupport;

/**
 * jqwik property test for the Entity Property Mapping endpoints' ADMIN-only authorization posture
 * (entity-api spec, Task 9.2), driven through MockMvc with {@code spring-security-test}.
 *
 * <p><strong>Feature: entity-api, Property 7: Authorization — ADMIN-only across every endpoint.</strong>
 * Over the full matrix of caller role {@code {OWNER, STAFF, TENANT}} plus the anonymous
 * (no-token) caller, endpoint {@code {create, list, get, update, delete}}, and id shape
 * {@code {existing, non-existent, malformed}}:
 * <ul>
 *   <li>a non-ADMIN <em>authenticated</em> caller is rejected with {@code 403 Forbidden}
 *       <em>before</em> any service/repository call runs — proven by asserting the mocked
 *       {@link EntityPropertyMappingService} records <em>zero</em> interactions
 *       ({@link org.mockito.Mockito#verifyNoInteractions}) for that request (Req 13.3, 13.4, 13.5);</li>
 *   <li>an <em>unauthenticated</em> caller (no JWT) is rejected with {@code 401 Unauthorized},
 *       again with zero service interactions (Req 13.2 posture; the gate never runs business logic);</li>
 *   <li>an <em>ADMIN</em> caller is never rejected by the authorization gate — the response is
 *       never {@code 401} and never {@code 403} regardless of endpoint or id shape (Req 13.1).</li>
 * </ul>
 *
 * <p><strong>How roles/auth are simulated.</strong> {@code @authz.isAdmin()} resolves the caller
 * role from the {@link UserIdentity} attached as the authentication <em>details</em> of the
 * {@link JwtAuthenticationToken} — exactly what {@code CustomJwtAuthenticationConverter} produces
 * from a validated JWT. So an authenticated caller of a given role is simulated with the
 * {@code authentication(...)} request post-processor carrying a {@link JwtAuthenticationToken} whose
 * details are a {@code UserIdentity} of that role (and a {@code ROLE_<role>} authority, matching the
 * converter). The anonymous caller sends no post-processor so the security filter chain rejects it
 * with {@code 401} via the configured {@code CustomAuthenticationEntryPoint}. A {@link MockBean}
 * {@link JwtDecoder} keeps the resource-server decoder from attempting a JWKS fetch at context start.
 *
 * <p>The {@link EntityPropertyMappingService} is a {@link MockBean}; the ADMIN happy-path stubs
 * {@code list(...)} to return an empty page so the list endpoint renders cleanly. For non-ADMIN /
 * anonymous requests the mock must never be touched, which is the core of the authorization claim.
 *
 * <p><strong>Validates: Requirements 13.1, 13.3, 13.4, 13.5</strong>
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
class EntityMappingAuthorizationPropertyTest {

    private static final String BASE = "/api/v1/entity-property-mappings";

    @Autowired
    private MockMvc mockMvc;

    /**
     * The service is mocked so the test can assert it is never invoked for a non-ADMIN / anonymous
     * caller (the "403/401 before any service call" guarantee).
     */
    @MockBean
    private EntityPropertyMappingService service;

    /**
     * Mocked so the resource-server {@link JwtDecoder} bean does not attempt a JWKS network fetch at
     * context start; authenticated requests bypass it via the {@code authentication(...)}
     * post-processor and anonymous requests never reach it.
     */
    @MockBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------------
    // Property 7a: a non-ADMIN authenticated caller is 403 before any service call
    // ------------------------------------------------------------------------

    /**
     * For every non-ADMIN role, every endpoint, and every id shape, an authenticated non-ADMIN
     * caller is rejected and the mocked service records zero interactions — the authorization gate
     * (and all other pre-handler checks) run before any business logic (Req 13.3, 13.4, 13.5).
     *
     * <p>Status detail: when the request reaches the {@code @PreAuthorize} gate it is rejected with
     * {@code 403 Forbidden}. For a malformed-UUID {@code {id}} path on the get/update/delete routes,
     * Spring fails to bind the {@code @PathVariable UUID id} and raises a
     * {@code MethodArgumentTypeMismatchException} ({@code 400}) <em>before</em> the method-level gate
     * interceptor runs — but still before any service call. Either way the controller never reaches
     * the service, which is the authoritative "no business logic for an unauthorized caller" claim.
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void nonAdminAuthenticatedCaller_isForbiddenBeforeAnyServiceCall(
            @ForAll("nonAdminRoles") UserRole role,
            @ForAll("endpoints") Endpoint endpoint,
            @ForAll("idShapes") IdShape idShape) throws Exception {

        clearInvocations(service);

        int expected = reachesGate(endpoint, idShape) ? 403 : 400;

        mockMvc.perform(request(endpoint, idShape).with(authentication(tokenForRole(role))))
                .andExpect(status().is(expected));

        // Whether the gate (403) or path-binding (400) rejected it, no business logic ran.
        verifyNoInteractions(service);
    }

    // ------------------------------------------------------------------------
    // Property 7b: an unauthenticated caller is 401 before any service call
    // ------------------------------------------------------------------------

    /**
     * For every endpoint and every id shape, an unauthenticated caller (no JWT) receives
     * {@code 401 Unauthorized} and the mocked service records zero interactions (Req 13.5 posture:
     * authorization/authentication is evaluated before any endpoint business logic).
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void unauthenticatedCaller_isUnauthorizedBeforeAnyServiceCall(
            @ForAll("endpoints") Endpoint endpoint,
            @ForAll("idShapes") IdShape idShape) throws Exception {

        clearInvocations(service);

        // No authentication post-processor -> anonymous request.
        mockMvc.perform(request(endpoint, idShape))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    // ------------------------------------------------------------------------
    // Property 7c: an ADMIN caller is never rejected by the gate (never 401, never 403)
    // ------------------------------------------------------------------------

    /**
     * For every endpoint and every id shape, an authenticated ADMIN caller is never rejected by the
     * authorization gate: the response status is never {@code 401} and never {@code 403} (Req 13.1).
     * The service is stubbed leniently so downstream rendering succeeds for the valid-id paths.
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void adminCaller_isNeverRejectedByTheGate(
            @ForAll("endpoints") Endpoint endpoint,
            @ForAll("idShapes") IdShape idShape) throws Exception {

        clearInvocations(service);
        stubServiceForAdminHappyPath();

        int statusCode = mockMvc.perform(
                        request(endpoint, idShape).with(authentication(tokenForRole(UserRole.ADMIN))))
                .andReturn()
                .getResponse()
                .getStatus();

        // The ADMIN gate admits the caller: whatever the downstream status (200/201/204/400/404),
        // it is never the authorization-gate rejections.
        org.assertj.core.api.Assertions.assertThat(statusCode)
                .as("ADMIN must never be rejected by the @PreAuthorize gate (endpoint=%s, id=%s)",
                        endpoint, idShape)
                .isNotEqualTo(401)
                .isNotEqualTo(403);
    }

    // ------------------------------------------------------------------------
    // Request building
    // ------------------------------------------------------------------------

    private enum Endpoint { CREATE, LIST, GET, UPDATE, DELETE }

    private enum IdShape { EXISTING, NON_EXISTENT, MALFORMED }

    /**
     * Whether a request reaches the method-level {@code @PreAuthorize} gate (vs. being rejected
     * earlier by {@code @PathVariable UUID} binding). Only the {@code {id}} routes carry a path
     * variable, and only a malformed id fails binding first; everything else reaches the gate.
     */
    private static boolean reachesGate(Endpoint endpoint, IdShape idShape) {
        boolean hasPathVariable =
                endpoint == Endpoint.GET || endpoint == Endpoint.UPDATE || endpoint == Endpoint.DELETE;
        return !(hasPathVariable && idShape == IdShape.MALFORMED);
    }

    private static final String VALID_CREATE_BODY =
            "{\"entityType\":\"LEAD\",\"jsonProperty\":\"firstName\",\"placeholder\":\"first-name\"}";
    private static final String VALID_PATCH_BODY = "{\"jsonProperty\":\"lastName\"}";

    /** Builds the MockMvc request for the given endpoint and id shape (id shape only matters for {id} routes). */
    private MockHttpServletRequestBuilder request(Endpoint endpoint, IdShape idShape) {
        String id = idSegment(idShape);
        switch (endpoint) {
            case CREATE:
                return post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY);
            case LIST:
                return get(BASE);
            case GET:
                return get(BASE + "/" + id);
            case UPDATE:
                return patch(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PATCH_BODY);
            case DELETE:
                return delete(BASE + "/" + id);
            default:
                throw new IllegalStateException("unhandled endpoint " + endpoint);
        }
    }

    private static String idSegment(IdShape idShape) {
        switch (idShape) {
            case EXISTING:
            case NON_EXISTENT:
                // Both are syntactically valid UUIDs; "existing vs non-existent" is immaterial to
                // the gate, which runs before any lookup. The service is mocked regardless.
                return UUID.randomUUID().toString();
            case MALFORMED:
                return "not-a-uuid";
            default:
                throw new IllegalStateException("unhandled id shape " + idShape);
        }
    }

    // ------------------------------------------------------------------------
    // Authentication simulation
    // ------------------------------------------------------------------------

    /**
     * Builds a {@link JwtAuthenticationToken} shaped exactly like {@code CustomJwtAuthenticationConverter}
     * produces for an interactive user of the given role: a {@code ROLE_<role>} authority and a
     * {@link UserIdentity} of that role attached as the authentication details — which is what
     * {@code @authz.isAdmin()} reads via {@code getDetails()}.
     */
    private static JwtAuthenticationToken tokenForRole(UserRole role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-" + role.name().toLowerCase())
                .claim("custom:role", role.name())
                .build();
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        token.setDetails(new UserIdentity(jwt.getSubject(), role, UUID.randomUUID()));
        return token;
    }

    /**
     * Lenient stubs so the ADMIN happy-path renders cleanly for the endpoints whose id is valid:
     * {@code list} returns an empty page, and {@code create}/{@code get}/{@code update} return a
     * fully-populated mapping so {@code EntityPropertyMappingResponse.from(...)} succeeds. This test
     * only cares that the gate admits ADMIN (status never 401/403); the stubs merely avoid a
     * downstream {@code 500} masking that signal.
     */
    private void stubServiceForAdminHappyPath() {
        Page<EntityPropertyMapping> emptyPage = new PageImpl<>(List.of(), Pageable.ofSize(20), 0);
        lenient().when(service.list(any(), any())).thenReturn(emptyPage);
        lenient().when(service.create(any())).thenReturn(sampleMapping());
        lenient().when(service.get(any())).thenReturn(sampleMapping());
        lenient().when(service.update(any(), any())).thenReturn(sampleMapping());
    }

    /** A fully-populated mapping so the response mapper has non-null fields to carry through. */
    private static EntityPropertyMapping sampleMapping() {
        EntityPropertyMapping mapping = new EntityPropertyMapping();
        mapping.setId(UUID.randomUUID());
        mapping.setEntityType(com.storagemanager.communications.model.EntityType.LEAD);
        mapping.setJsonProperty("firstName");
        mapping.setPlaceholder("first-name");
        java.time.Instant now = java.time.Instant.now();
        mapping.setCreatedAt(now);
        mapping.setUpdatedAt(now);
        return mapping;
    }

    // ------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------

    @Provide
    Arbitrary<UserRole> nonAdminRoles() {
        return Arbitraries.of(UserRole.OWNER, UserRole.STAFF, UserRole.TENANT);
    }

    @Provide
    Arbitrary<Endpoint> endpoints() {
        return Arbitraries.of(Endpoint.values());
    }

    @Provide
    Arbitrary<IdShape> idShapes() {
        return Arbitraries.of(IdShape.values());
    }
}
