package com.storagemanager.communications.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagemanager.communications.dto.CreateEntityPropertyMappingRequest;
import com.storagemanager.communications.dto.UpdateEntityPropertyMappingRequest;
import com.storagemanager.communications.exception.EntityNotFoundException;
import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;
import com.storagemanager.communications.service.EntityPropertyMappingService;

/**
 * Example-based MockMvc web tests for {@link EntityPropertyMappingController} (task 9.5).
 *
 * <p>This boots the full application via {@link SpringBootTest} + {@link AutoConfigureMockMvc} so
 * the real security filter chain, the {@link EntityPropertyMappingController}, the
 * {@link GlobalExceptionHandler}, and the {@code authz} ({@code AuthorizationService}) bean all
 * participate. The {@link EntityPropertyMappingService} is {@link MockBean mocked} so each test
 * drives a specific controller/wiring/status/exception path without touching a real repository.
 *
 * <p>Security wiring is exercised hermetically: a {@link MockBean} {@link JwtDecoder} prevents the
 * resource-server decoder bean from attempting a JWKS fetch at context start. Authenticated ADMIN
 * callers are supplied via spring-security-test's {@code authentication()} request post-processor
 * carrying a {@link JwtAuthenticationToken} whose {@code details} is an ADMIN {@link UserIdentity}
 * (exactly the shape {@code CustomJwtAuthenticationConverter} produces), which {@code @authz.isAdmin()}
 * reads. All callers in these tests are ADMIN — authorization edge-cases for non-ADMIN callers are
 * covered by Property 7 in a separate test. The one no-authentication test sends no post-processor
 * so the anonymous request is rejected with {@code 401}.
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 4.1, 4.3, 5.5, 5.6, 5.7, 6.1, 6.5, 6.6, 6.8, 6.9,
 * 7.1, 7.3, 7.4, 8.1, 8.6, 8.7, 8.8, 9.1, 9.2, 9.3, 9.4, 13.2
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:comm_epm_mvc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class EntityPropertyMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EntityPropertyMappingService service;

    /** Mocked so the resource-server {@link JwtDecoder} bean does not attempt a JWKS network fetch. */
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final String BASE_PATH = "/api/v1/entity-property-mappings";

    // ------------------------------------------------------------------------
    // Enum facts (Req 2.1, 2.2)
    // ------------------------------------------------------------------------

    @Test
    void entityTypeEnumDefinesExactlyLead() {
        assertThat(EntityType.valueOf("LEAD")).isEqualTo(EntityType.LEAD);
        assertThat(EntityType.values()).hasSize(1);
    }

    // ------------------------------------------------------------------------
    // Create — POST -> 201 with body; LEAD string round-trips (Req 4.1, 4.3, 2.3)
    // ------------------------------------------------------------------------

    @Test
    void createReturns201WithBodyAndPersistsLeadString() throws Exception {
        EntityPropertyMapping saved = mapping(UUID.randomUUID(), EntityType.LEAD, "firstName", "first-name");
        when(service.create(any(CreateEntityPropertyMappingRequest.class))).thenReturn(saved);

        String body = """
                {"entityType":"LEAD","jsonProperty":"firstName","placeholder":"first-name"}
                """;

        mockMvc.perform(post(BASE_PATH)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(saved.getId().toString()))
                .andExpect(jsonPath("$.entityType").value("LEAD"))
                .andExpect(jsonPath("$.jsonProperty").value("firstName"))
                .andExpect(jsonPath("$.placeholder").value("first-name"));

        ArgumentCaptor<CreateEntityPropertyMappingRequest> captor =
                ArgumentCaptor.forClass(CreateEntityPropertyMappingRequest.class);
        verify(service).create(captor.capture());
        assertThat(captor.getValue().entityType()).isEqualTo(EntityType.LEAD);
    }

    // ------------------------------------------------------------------------
    // Create validation / framework behavior (Req 5.5, 5.6, 5.7, 2.4)
    // ------------------------------------------------------------------------

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not valid json "))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    @Test
    void unknownEntityTypeInBodyReturns400NamingEntityType() throws Exception {
        String body = """
                {"entityType":"CUSTOMER","jsonProperty":"firstName","placeholder":"first-name"}
                """;

        mockMvc.perform(post(BASE_PATH)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("entityType")));

        verify(service, never()).create(any());
    }

    @Test
    void multipleValidationViolationsAggregatedIntoOne400ListingFields() throws Exception {
        // entityType null, jsonProperty blank, placeholder blank -> all three fail @Valid.
        String body = """
                {"entityType":null,"jsonProperty":"","placeholder":""}
                """;

        mockMvc.perform(post(BASE_PATH)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("entityType")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("jsonProperty")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("placeholder")));

        verify(service, never()).create(any());
    }

    // ------------------------------------------------------------------------
    // List — GET -> 200 (Req 6.1); defaults page=0/size=20 (Req 6.6)
    // ------------------------------------------------------------------------

    @Test
    void listReturns200WithDefaultPageAndSize() throws Exception {
        EntityPropertyMapping m = mapping(UUID.randomUUID(), EntityType.LEAD, "firstName", "first-name");
        Page<EntityPropertyMapping> page =
                new PageImpl<>(List.of(m), Pageable.ofSize(20).withPage(0), 1);
        when(service.list(eq(null), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(BASE_PATH).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(eq(null), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void listWithEntityTypeFilterPassesFilterToService() throws Exception {
        Page<EntityPropertyMapping> page =
                new PageImpl<>(List.of(), Pageable.ofSize(20).withPage(0), 0);
        when(service.list(eq(EntityType.LEAD), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(BASE_PATH).param("entityType", "LEAD").with(admin()))
                .andExpect(status().isOk());

        verify(service).list(eq(EntityType.LEAD), any(Pageable.class));
    }

    // Req 6.5: unknown entityType query param -> 400.
    @Test
    void listWithUnknownEntityTypeQueryParamReturns400() throws Exception {
        mockMvc.perform(get(BASE_PATH).param("entityType", "CUSTOMER").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("entityType")));

        verify(service, never()).list(any(), any());
    }

    // Req 6.6: page < 0 -> 400.
    @Test
    void listWithNegativePageReturns400() throws Exception {
        mockMvc.perform(get(BASE_PATH).param("page", "-1").with(admin()))
                .andExpect(status().isBadRequest());

        verify(service, never()).list(any(), any());
    }

    // Req 6.6: size < 1 -> 400.
    @Test
    void listWithSizeBelowOneReturns400() throws Exception {
        mockMvc.perform(get(BASE_PATH).param("size", "0").with(admin()))
                .andExpect(status().isBadRequest());

        verify(service, never()).list(any(), any());
    }

    // Req 6.8: size > 100 is capped to 100; service receives Pageable with size 100 and the
    // response metadata reports 100.
    @Test
    void listWithOversizedSizeIsCappedTo100() throws Exception {
        // Report the effective capped size back through the page metadata (pageable size 100).
        Page<EntityPropertyMapping> page =
                new PageImpl<>(List.of(), Pageable.ofSize(100).withPage(0), 0);
        when(service.list(eq(null), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get(BASE_PATH).param("size", "500").with(admin()))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(eq(null), captor.capture());
        assertThat(captor.getValue().getPageSize())
                .as("size > 100 must be capped to 100 before reaching the service")
                .isEqualTo(100);
    }

    // Req 6.9: empty result -> 200 with empty content and totalElements = 0.
    @Test
    void listWithNoMatchesReturns200EmptyContentZeroTotal() throws Exception {
        Page<EntityPropertyMapping> empty =
                new PageImpl<>(List.of(), Pageable.ofSize(20).withPage(0), 0);
        when(service.list(eq(null), any(Pageable.class))).thenReturn(empty);

        mockMvc.perform(get(BASE_PATH).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------------
    // Get by id — GET /{id} -> 200 (Req 7.1); 404 (Req 7.3); non-UUID -> 400 (Req 7.4)
    // ------------------------------------------------------------------------

    @Test
    void getByIdReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(mapping(id, EntityType.LEAD, "email", "email"));

        mockMvc.perform(get(BASE_PATH + "/" + id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.entityType").value("LEAD"));
    }

    @Test
    void getByIdNonExistentReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new EntityNotFoundException("EntityPropertyMapping", id));

        mockMvc.perform(get(BASE_PATH + "/" + id).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByIdNonUuidReturns400() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/not-a-uuid").with(admin()))
                .andExpect(status().isBadRequest());

        verify(service, never()).get(any());
    }

    // ------------------------------------------------------------------------
    // Update — PATCH /{id} -> 200 (Req 8.1); 404 (Req 8.7); non-UUID -> 400 (Req 8.8);
    // unknown entityType body -> 400 (Req 8.6)
    // ------------------------------------------------------------------------

    @Test
    void updateReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        EntityPropertyMapping updated = mapping(id, EntityType.LEAD, "lastName", "last-name");
        when(service.update(eq(id), any(UpdateEntityPropertyMappingRequest.class))).thenReturn(updated);

        String body = """
                {"placeholder":"last-name"}
                """;

        mockMvc.perform(patch(BASE_PATH + "/" + id)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeholder").value("last-name"));
    }

    @Test
    void updateNonExistentReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.update(eq(id), any(UpdateEntityPropertyMappingRequest.class)))
                .thenThrow(new EntityNotFoundException("EntityPropertyMapping", id));

        mockMvc.perform(patch(BASE_PATH + "/" + id)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeholder\":\"last-name\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateNonUuidReturns400() throws Exception {
        mockMvc.perform(patch(BASE_PATH + "/not-a-uuid")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeholder\":\"last-name\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(any(), any());
    }

    @Test
    void updateUnknownEntityTypeInBodyReturns400() throws Exception {
        UUID id = UUID.randomUUID();
        String body = """
                {"entityType":"CUSTOMER","placeholder":"last-name"}
                """;

        mockMvc.perform(patch(BASE_PATH + "/" + id)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("entityType")));

        verify(service, never()).update(any(), any());
    }

    // ------------------------------------------------------------------------
    // Delete — DELETE /{id} -> 204 (Req 9.1, 9.2); 404 (Req 9.3); non-UUID -> 400 (Req 9.4)
    // ------------------------------------------------------------------------

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(service).delete(id);

        mockMvc.perform(delete(BASE_PATH + "/" + id).with(admin()))
                .andExpect(status().isNoContent());

        verify(service).delete(id);
    }

    @Test
    void deleteNonExistentReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new EntityNotFoundException("EntityPropertyMapping", id)).when(service).delete(id);

        mockMvc.perform(delete(BASE_PATH + "/" + id).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteNonUuidReturns400() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/not-a-uuid").with(admin()))
                .andExpect(status().isBadRequest());

        verify(service, never()).delete(any());
    }

    // Req 9.1, 9.2, 9.3 (delete behavior): create -> delete (204) -> get -> 404 (simulated via mock).
    @Test
    void createThenDeleteThenGetReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        EntityPropertyMapping saved = mapping(id, EntityType.LEAD, "firstName", "first-name");
        when(service.create(any(CreateEntityPropertyMappingRequest.class))).thenReturn(saved);

        mockMvc.perform(post(BASE_PATH)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entityType\":\"LEAD\",\"jsonProperty\":\"firstName\",\"placeholder\":\"first-name\"}"))
                .andExpect(status().isCreated());

        doNothing().when(service).delete(id);
        mockMvc.perform(delete(BASE_PATH + "/" + id).with(admin()))
                .andExpect(status().isNoContent());

        // After deletion, a subsequent get is not found (service now throws).
        when(service.get(id)).thenThrow(new EntityNotFoundException("EntityPropertyMapping", id));
        mockMvc.perform(get(BASE_PATH + "/" + id).with(admin()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------
    // Authentication — missing token -> 401 (Req 13.2)
    // ------------------------------------------------------------------------

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isUnauthorized());

        verify(service, never()).list(any(), any());
    }

    @Test
    void missingTokenOnCreateReturns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entityType\":\"LEAD\",\"jsonProperty\":\"firstName\",\"placeholder\":\"first-name\"}"))
                .andExpect(status().isUnauthorized());

        verify(service, never()).create(any());
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static EntityPropertyMapping mapping(UUID id, EntityType type, String jsonProperty,
                                                 String placeholder) {
        EntityPropertyMapping m = new EntityPropertyMapping();
        m.setId(id);
        m.setEntityType(type);
        m.setJsonProperty(jsonProperty);
        m.setPlaceholder(placeholder);
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return m;
    }

    /**
     * Builds an authenticated ADMIN caller as a {@link JwtAuthenticationToken} whose {@code details}
     * is an ADMIN {@link UserIdentity} — exactly the shape {@code CustomJwtAuthenticationConverter}
     * attaches and {@code @authz.isAdmin()} reads via {@code getDetails()}.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        Jwt jwt = Jwt.withTokenValue("admin-token")
                .header("alg", "none")
                .subject("admin-sub")
                .claim("custom:role", "ADMIN")
                .build();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities, "admin-sub");
        token.setDetails(new UserIdentity("admin-sub", UserRole.ADMIN, UUID.randomUUID()));
        return authentication(token);
    }
}
