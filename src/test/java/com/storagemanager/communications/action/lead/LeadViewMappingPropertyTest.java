package com.storagemanager.communications.action.lead;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storagemanager.communications.action.lead.LeadReadResponse.ContactMethodDto;
import com.storagemanager.communications.model.MessageType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for {@link HttpLeadReadPort} — the 200-response → {@link LeadView}
 * field mapping.
 *
 * <p><b>Feature: live-lead-read-port, Property 1: Response to LeadView mapping is faithful</b> —
 * over arbitrary well-formed 200 lead bodies, {@code HttpLeadReadPort.findLead} projects the
 * response onto a {@link LeadView} whose fields equal the corresponding response fields:
 * {@code organizationId}, {@code facilityId}, {@code email}, {@code phone == mobilePhone},
 * {@code preferredMethod ==} the {@link MessageType} with the same name as
 * {@code preferredContactMethod}, and {@code properties} equal to the response map (an empty map
 * when the JSON omits {@code properties}).
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7</b>
 *
 * <p>Each iteration builds an independent {@link RestClient} bound to a fresh
 * {@link MockRestServiceServer} (a mock server expects a fixed call count, so rebuilding per try
 * isolates iterations). The 200 body is serialized from a JSON tree whose field names match the
 * api's response ({@code organizationId}, {@code facilityId}, {@code email}, {@code mobilePhone},
 * {@code preferredContactMethod}, {@code properties}); the {@code properties} field is omitted
 * entirely when the generated scenario exercises the "absent properties" case.
 */
class LeadViewMappingPropertyTest {

    private static final String BASE_URL = "http://api.test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Feature: live-lead-read-port, Property 1: Response to LeadView mapping is faithful.
     *
     * <p><b>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7</b>
     */
    @Property(tries = 100)
    @Label("Feature: live-lead-read-port, Property 1: Response to LeadView mapping is faithful (Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7)")
    void property1_responseToLeadViewMappingIsFaithful(@ForAll("leadBodies") LeadBody body) throws Exception {
        UUID leadId = UUID.randomUUID();
        String path = BASE_URL + "/api/v1/leads/" + leadId;

        // Independent client/server per iteration (each expects exactly one request).
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpLeadReadPort port = new HttpLeadReadPort(builder.build());

        server.expect(requestTo(path))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body.toJson(), MediaType.APPLICATION_JSON));

        Optional<LeadView> result = port.findLead(leadId);
        server.verify();

        assertThat(result).as("a well-formed 200 body yields a populated LeadView").isPresent();
        LeadView view = result.get();

        assertThat(view.organizationId())
                .as("organizationId maps from facility.organizationId")
                .isEqualTo(body.organizationId);
        assertThat(view.facilityId()).as("facilityId maps faithfully (including null)").isEqualTo(body.facilityId);
        assertThat(view.email()).as("email maps faithfully").isEqualTo(body.email);
        assertThat(view.phone()).as("phone == mobilePhone").isEqualTo(body.mobilePhone);
        assertThat(view.preferredMethod())
                .as("preferredMethod == MessageType by name")
                .isEqualTo(MessageType.valueOf(body.preferredContactMethod.name()));
        assertThat(view.properties())
                .as("properties == non-null personalizable fields keyed by JSON name")
                .isEqualTo(body.expectedProperties());
    }

    // ------------------------------------------------------------------------
    // Generated input
    // ------------------------------------------------------------------------

    /**
     * A well-formed lead response in the new api shape. {@code organizationId} lives under the
     * nested {@code facility} object; the remaining fields are top-level lead fields. The
     * personalizable string fields ({@code firstName}, {@code lastName}, {@code homePhone},
     * {@code city}, {@code state}, {@code postalCode}, {@code dateOfBirth}, {@code source}) may be
     * null (absent from the emitted JSON) or present.
     */
    record LeadBody(
            UUID organizationId,
            UUID facilityId,
            String email,
            String mobilePhone,
            ContactMethodDto preferredContactMethod,
            String firstName,
            String lastName,
            String homePhone,
            String city,
            String state,
            String postalCode,
            String dateOfBirth,
            String source) {

        /**
         * The expected {@code properties} map, mirroring {@link HttpLeadReadPort} buildProperties:
         * only non-null fields, keyed by their JSON name, in the same insertion order, and
         * {@code preferredContactMethod} rendered as the enum constant name.
         */
        Map<String, Object> expectedProperties() {
            Map<String, Object> expected = new LinkedHashMap<>();
            putIfPresent(expected, "firstName", firstName);
            putIfPresent(expected, "lastName", lastName);
            putIfPresent(expected, "email", email);
            putIfPresent(expected, "homePhone", homePhone);
            putIfPresent(expected, "mobilePhone", mobilePhone);
            putIfPresent(expected, "city", city);
            putIfPresent(expected, "state", state);
            putIfPresent(expected, "postalCode", postalCode);
            putIfPresent(expected, "dateOfBirth", dateOfBirth);
            putIfPresent(expected, "source", source);
            expected.put("preferredContactMethod", preferredContactMethod.name());
            return expected;
        }

        private static void putIfPresent(Map<String, Object> target, String key, Object value) {
            if (value != null) {
                target.put(key, value);
            }
        }

        /** Serializes a JSON body in the new api shape: top-level lead fields + nested facility. */
        String toJson() throws Exception {
            ObjectNode node = MAPPER.createObjectNode();
            if (facilityId == null) {
                node.putNull("facilityId");
            } else {
                node.put("facilityId", facilityId.toString());
            }
            putOrNull(node, "firstName", firstName);
            putOrNull(node, "lastName", lastName);
            node.put("email", email);
            putOrNull(node, "homePhone", homePhone);
            node.put("mobilePhone", mobilePhone);
            putOrNull(node, "city", city);
            putOrNull(node, "state", state);
            putOrNull(node, "postalCode", postalCode);
            putOrNull(node, "dateOfBirth", dateOfBirth);
            putOrNull(node, "source", source);
            node.put("preferredContactMethod", preferredContactMethod.name());

            ObjectNode facility = MAPPER.createObjectNode();
            facility.put("id", UUID.randomUUID().toString());
            facility.put("organizationId", organizationId.toString());
            facility.put("name", "Facility");
            node.set("facility", facility);

            return MAPPER.writeValueAsString(node);
        }

        private static void putOrNull(ObjectNode node, String key, String value) {
            if (value == null) {
                node.putNull(key);
            } else {
                node.put(key, value);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Generators (smart: constrained to the well-formed 200-body input space)
    // ------------------------------------------------------------------------

    @Provide
    Arbitrary<LeadBody> leadBodies() {
        // jqwik's Combinators.combine supports at most 8 arbitraries, so the lead's 13 inputs are
        // built in two stages: a "core" record of the always-relevant mapping inputs, then the
        // remaining personalization strings folded in via flatMap.
        Arbitrary<Core> cores = Combinators.combine(
                        uuids(),                             // organizationId (under facility)
                        uuids().injectNull(0.25),            // facilityId (nullable)
                        emails(),
                        phones(),
                        Arbitraries.of(ContactMethodDto.class))
                .as(Core::new);

        return cores.flatMap(core -> Combinators.combine(
                        personalStrings(),        // firstName
                        personalStrings(),        // lastName
                        phones().injectNull(0.4), // homePhone
                        personalStrings(),        // city
                        personalStrings(),        // state
                        personalStrings(),        // postalCode
                        personalStrings(),        // dateOfBirth
                        personalStrings())        // source
                .as((firstName, lastName, homePhone, city, state, postalCode, dob, source) ->
                        new LeadBody(
                                core.organizationId(),
                                core.facilityId(),
                                core.email(),
                                core.mobilePhone(),
                                core.preferredContactMethod(),
                                firstName,
                                lastName,
                                homePhone,
                                city,
                                state,
                                postalCode,
                                dob,
                                source)));
    }

    /** Core mapping inputs combined first (combine supports at most 8 arbitraries). */
    private record Core(
            UUID organizationId,
            UUID facilityId,
            String email,
            String mobilePhone,
            ContactMethodDto preferredContactMethod) {}

    private Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    private Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> domain = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        return Combinators.combine(local, domain).as((l, d) -> l + "@" + d + ".com");
    }

    private Arbitrary<String> phones() {
        return Arbitraries.strings().numeric().ofMinLength(10).ofMaxLength(10).map(digits -> "+1" + digits);
    }

    /**
     * Arbitrary nullable-or-present personalization strings (alphanumeric). A null value models an
     * absent field (omitted from {@code properties}); a present value must round-trip through JSON
     * and reappear in the map keyed by its JSON name.
     */
    private Arbitrary<String> personalStrings() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12).injectNull(0.3);
    }
}
