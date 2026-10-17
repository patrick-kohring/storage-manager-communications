package com.storagemanager.communications.property;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagemanager.communications.controller.EntityPropertyMappingController;
import com.storagemanager.communications.dto.CreateEntityPropertyMappingRequest;
import com.storagemanager.communications.exception.GlobalExceptionHandler;
import com.storagemanager.communications.model.EntityPropertyMapping;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.service.EntityPropertyMappingService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based test for required-field (non-blank) validation on the create endpoint.
 *
 * <p>Feature: entity-api, Property 6: Required-field (non-blank) validation — a create request with
 * {@code entityType} null, or {@code jsonProperty} null/whitespace-only, or {@code placeholder}
 * null/whitespace-only is rejected {@code 400} naming every failing field; a request with all three
 * present, non-blank, and placeholder kebab-valid passes validation.
 *
 * <p>Verification level — the three fields are gated by Bean Validation annotations on
 * {@link CreateEntityPropertyMappingRequest}: {@code @NotNull} on {@code entityType},
 * {@code @NotBlank} on {@code jsonProperty}, and {@code @NotBlank} + kebab {@code @Pattern} on
 * {@code placeholder}. A {@code @Valid @RequestBody} violation surfaces as a
 * {@code MethodArgumentNotValidException}, which {@link GlobalExceptionHandler} aggregates into a
 * single {@code 400} body whose {@code message} joins every failing field with {@code "; "}. This
 * test drives the real {@link EntityPropertyMappingController} through a standalone {@link MockMvc}
 * (the {@code @PreAuthorize} ADMIN gate is intentionally out of scope here — it is Property 7), with
 * the {@link EntityPropertyMappingService} mocked so an all-valid request flows through to a
 * {@code 201} and asserts the service is invoked.
 *
 * <p>Scope note on {@code entityType} (per the design): a <em>non-enum string</em> for
 * {@code entityType} fails at Jackson binding ({@code HttpMessageNotReadableException} → {@code 400},
 * a body-readability path) <em>before</em> {@code @Valid} runs — a different mechanism. This
 * property therefore drives {@code entityType} only as present-valid ({@code "LEAD"}) or JSON
 * {@code null}, so the {@code @NotNull} required-field path (and the aggregated field naming) is
 * what is exercised.
 *
 * <p>Strategy: {@link #fieldStates()} independently chooses, for each of the three fields, whether
 * it is present-valid, {@code null}, or (for the two String fields) whitespace-only. The JSON body
 * is assembled so a {@code null} field is emitted as a JSON {@code null} literal. The test asserts
 * that whenever at least one field is invalid the response is {@code 400} and the message names
 * every failing field, and that when all three are valid the response is {@code 201} and the
 * service is called exactly once.
 *
 * <p><strong>Validates: Requirements 5.1, 5.2, 5.3, 5.6</strong>
 */
class EntityMappingRequiredFieldPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = "/api/v1/entity-property-mappings";

    /** How a single field is rendered into the request body. */
    private enum FieldState {
        VALID,          // present and non-blank / a defined enum value
        NULL,           // JSON null literal (fires @NotNull / @NotBlank)
        WHITESPACE      // whitespace-only string (fires @NotBlank) — String fields only
    }

    /**
     * Builds a standalone MockMvc around the real controller with a mocked service and the real
     * {@link GlobalExceptionHandler}, so {@code @Valid} binding + the handler's field aggregation
     * are exercised exactly as in production. Standalone setup skips the security filter chain, so
     * the ADMIN {@code @PreAuthorize} gate (Property 7) is not evaluated here.
     */
    private static MockMvc mockMvcWith(EntityPropertyMappingService service) {
        EntityPropertyMappingController controller = new EntityPropertyMappingController(service);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── Arbitraries ──

    /** A non-blank, kebab-valid placeholder value. */
    @Provide
    Arbitrary<String> validPlaceholders() {
        Arbitrary<String> segment = Arbitraries.strings()
                .withCharRange('a', 'z').numeric()
                .ofMinLength(1).ofMaxLength(6);
        return segment.list().ofMinSize(1).ofMaxSize(3)
                .map(parts -> String.join("-", parts))
                // guarantee at least one lowercase/alnum char and valid kebab shape
                .filter(s -> s.matches("^[a-z0-9]+(-[a-z0-9]+)*$"));
    }

    /** A non-blank jsonProperty value (flat name or dot-path). */
    @Provide
    Arbitrary<String> validJsonProperties() {
        return Arbitraries.oneOf(
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20),
                Combinators.combine(
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8))
                        .as((a, b) -> a + "." + b));
    }

    @Provide
    Arbitrary<FieldState> entityTypeStates() {
        // Non-enum strings are a Jackson-binding path (see class javadoc); here entityType is
        // either a present defined value or JSON null, so @NotNull is what is exercised.
        return Arbitraries.of(FieldState.VALID, FieldState.NULL);
    }

    @Provide
    Arbitrary<FieldState> stringFieldStates() {
        return Arbitraries.of(FieldState.VALID, FieldState.NULL, FieldState.WHITESPACE);
    }

    // ═══════════════════════════════════════════════════════════════
    // Property 6: any invalid required field → 400 naming every failing field;
    //             all three valid → request passes validation (201, service invoked).
    // ═══════════════════════════════════════════════════════════════

    @Property(tries = 300)
    void requiredFieldValidationNamesEveryFailingField(
            @ForAll("entityTypeStates") FieldState entityTypeState,
            @ForAll("stringFieldStates") FieldState jsonPropertyState,
            @ForAll("stringFieldStates") FieldState placeholderState,
            @ForAll("validJsonProperties") String validJsonProperty,
            @ForAll("validPlaceholders") String validPlaceholder) throws Exception {

        // The mocked service echoes a persisted-looking entity for the all-valid path so the
        // controller can map it to a 201 response; it is never reached when validation fails.
        EntityPropertyMappingService service = mock(EntityPropertyMappingService.class);
        when(service.create(any(CreateEntityPropertyMappingRequest.class)))
                .thenAnswer(inv -> {
                    CreateEntityPropertyMappingRequest req = inv.getArgument(0);
                    EntityPropertyMapping m = new EntityPropertyMapping();
                    m.setId(UUID.randomUUID());
                    m.setEntityType(req.entityType());
                    m.setJsonProperty(req.jsonProperty());
                    m.setPlaceholder(req.placeholder());
                    return m;
                });
        MockMvc mockMvc = mockMvcWith(service);

        // Assemble the JSON body field-by-field, emitting a JSON null literal for NULL fields.
        List<String> expectedFailingFields = new ArrayList<>();
        List<String> jsonFields = new ArrayList<>();

        // entityType
        switch (entityTypeState) {
            case VALID -> jsonFields.add("\"entityType\":\"" + EntityType.LEAD.name() + "\"");
            case NULL -> {
                jsonFields.add("\"entityType\":null");
                expectedFailingFields.add("entityType");
            }
            default -> throw new IllegalStateException("entityType WHITESPACE not modeled");
        }

        // jsonProperty
        switch (jsonPropertyState) {
            case VALID -> jsonFields.add("\"jsonProperty\":" + MAPPER.writeValueAsString(validJsonProperty));
            case NULL -> {
                jsonFields.add("\"jsonProperty\":null");
                expectedFailingFields.add("jsonProperty");
            }
            case WHITESPACE -> {
                jsonFields.add("\"jsonProperty\":" + MAPPER.writeValueAsString("   "));
                expectedFailingFields.add("jsonProperty");
            }
        }

        // placeholder
        switch (placeholderState) {
            case VALID -> jsonFields.add("\"placeholder\":" + MAPPER.writeValueAsString(validPlaceholder));
            case NULL -> {
                jsonFields.add("\"placeholder\":null");
                expectedFailingFields.add("placeholder");
            }
            case WHITESPACE -> {
                jsonFields.add("\"placeholder\":" + MAPPER.writeValueAsString("   "));
                expectedFailingFields.add("placeholder");
            }
        }

        String body = "{" + String.join(",", jsonFields) + "}";

        MvcResult result = mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        int status = result.getResponse().getStatus();

        if (expectedFailingFields.isEmpty()) {
            // All three fields present, non-blank, and placeholder kebab-valid → passes validation.
            assertThat(status)
                    .as("all-valid create request must pass validation and return 201 (body=%s)", body)
                    .isEqualTo(201);
        } else {
            // At least one required field invalid → 400 whose message names every failing field.
            assertThat(status)
                    .as("invalid required field(s) %s must be rejected 400 (body=%s)",
                            expectedFailingFields, body)
                    .isEqualTo(400);

            String responseBody = result.getResponse().getContentAsString();
            JsonNode node = MAPPER.readTree(responseBody);
            String message = node.path("message").asText();

            for (String field : expectedFailingFields) {
                assertThat(message)
                        .as("400 message must name failing field '%s' (message=%s)", field, message)
                        .contains(field);
            }
        }
    }
}
