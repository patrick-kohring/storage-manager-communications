package com.storagemanager.communications.action.lead;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestClient;

import com.storagemanager.communications.model.MessageType;

/**
 * Example-based integration test for {@link HttpLeadReadPort} driven against a
 * {@link MockRestServiceServer} standing in for {@code storage-manager-api}. Unlike the jqwik
 * property tests, these are three concrete, fixed-value scenarios that document the end-to-end
 * happy path, the terminal not-found path, and a transient server-error path.
 *
 * <ul>
 *   <li><b>Happy path</b> — a 200 lead body yields a populated {@link LeadView} with the exact
 *       expected fields ({@code phone == mobilePhone}, {@code preferredMethod == MessageType.SMS},
 *       and the expected {@code properties} map). (Req 1.1, 1.3, 2.1, 2.4, 2.5, 3.2)</li>
 *   <li><b>Not found</b> — a 404 yields {@link Optional#empty()} and does not throw. (Req 3.1)</li>
 *   <li><b>Server error</b> — a 500 yields a {@link LeadReadException}. (Req 3.3)</li>
 * </ul>
 *
 * <p>Each test binds a fresh {@link MockRestServiceServer} to an independent {@link RestClient},
 * scripts a single GET to {@code /api/v1/leads/{id}}, and calls {@code server.verify()} to confirm
 * the adapter issued the GET to the expected lead path.
 */
class HttpLeadReadPortIntegrationTest {

    private static final String BASE_URL = "http://api.test";

    @Test
    void findLead_returnsPopulatedLeadView_onOk() {
        UUID leadId = UUID.randomUUID();
        UUID organizationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID facilityId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID facilitySummaryId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String email = "lead@example.test";
        String mobilePhone = "+15555550123";
        // Expected properties built the same way HttpLeadReadPort.buildProperties does: only
        // non-null lead fields, keyed by JSON name, with preferredContactMethod as the enum name.
        // homePhone is set null in the body below, so it is omitted here.
        Map<String, Object> expectedProperties = new LinkedHashMap<>();
        expectedProperties.put("firstName", "Jordan");
        expectedProperties.put("lastName", "Smith");
        expectedProperties.put("email", email);
        expectedProperties.put("mobilePhone", mobilePhone);
        expectedProperties.put("city", "Austin");
        expectedProperties.put("state", "TX");
        expectedProperties.put("postalCode", "78701");
        expectedProperties.put("dateOfBirth", "1990-01-02");
        expectedProperties.put("source", "WEBSITE");
        expectedProperties.put("preferredContactMethod", "SMS");

        String body = """
                {
                  "facilityId": "%s",
                  "firstName": "Jordan",
                  "lastName": "Smith",
                  "email": "%s",
                  "homePhone": null,
                  "mobilePhone": "%s",
                  "city": "Austin",
                  "state": "TX",
                  "postalCode": "78701",
                  "dateOfBirth": "1990-01-02",
                  "source": "WEBSITE",
                  "preferredContactMethod": "SMS",
                  "facility": {
                    "id": "%s",
                    "organizationId": "%s",
                    "name": "Downtown"
                  }
                }
                """.formatted(facilityId, email, mobilePhone, facilitySummaryId, organizationId);

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpLeadReadPort port = new HttpLeadReadPort(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/leads/" + leadId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Optional<LeadView> result = port.findLead(leadId);

        server.verify();

        assertThat(result).as("a 200 lead response yields a populated LeadView").isPresent();
        LeadView view = result.get();
        assertThat(view.organizationId())
                .as("organizationId maps from facility.organizationId")
                .isEqualTo(organizationId);
        assertThat(view.facilityId()).isEqualTo(facilityId);
        assertThat(view.email()).isEqualTo(email);
        assertThat(view.phone()).as("phone maps from mobilePhone").isEqualTo(mobilePhone);
        assertThat(view.preferredMethod())
                .as("preferredContactMethod SMS maps to MessageType.SMS")
                .isEqualTo(MessageType.SMS);
        assertThat(view.properties())
                .as("properties built from non-null lead fields keyed by JSON name")
                .isEqualTo(expectedProperties);
    }

    @Test
    void findLead_returnsEmpty_onNotFound() {
        UUID leadId = UUID.randomUUID();

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpLeadReadPort port = new HttpLeadReadPort(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/leads/" + leadId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<LeadView> result = port.findLead(leadId);

        server.verify();

        assertThat(result)
                .as("404 maps to Optional.empty() and does not throw (Req 3.1)")
                .isEmpty();
    }

    @Test
    void findLead_throwsLeadReadException_onServerError() {
        UUID leadId = UUID.randomUUID();

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpLeadReadPort port = new HttpLeadReadPort(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/leads/" + leadId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> port.findLead(leadId))
                .as("5xx maps to a retryable LeadReadException (Req 3.3)")
                .isInstanceOf(LeadReadException.class);

        server.verify();
    }
}
