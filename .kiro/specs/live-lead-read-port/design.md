# Design: Live Lead Read Port (communications → storage-manager-api)

## Overview

This design adds a live `LeadReadPort` implementation, `HttpLeadReadPort`, to
`storage-manager-communications`. It reads a single lead from
`storage-manager-api` (`GET /api/v1/leads/{id}`) and projects the response onto
the existing `LeadView`, so the `ActionProcessor` can process `SendCommunication`
actions against real lead data instead of the in-memory `StubLeadReadPort`.

The HTTP client, timeouts, and s2s auth seam deliberately mirror the service's
existing outbound transport to events (`EventsIngestTransport`,
`EventsIngestProperties`, `EventsAuthTokenProvider`, `MessagingTransportConfig`),
so the two outbound integrations are structurally symmetric and reviewers
recognize the pattern. Only the direction and target differ: here communications
calls storage-manager-api.

The existing `LeadReadPort` / `LeadView` / `LeadReadException` contracts are not
changed. The stub stays the implementation for `local`/`test`/`default`; the live
adapter activates in the non-local runtime profile.

### Constraints and assumptions

- **No api code is changed by this spec.** The adapter is written assuming
  storage-manager-api exposes (A1) an s2s/scope read path, (A2) `organizationId`
  on the lead response, and (A3) a `properties` map on the lead response. These
  are a separate, subsequent api-side change.
- **Port contract unchanged.** The adapter returns `Optional<LeadView>` /
  throws `LeadReadException`; outcome classification into
  `ActionValidationException` / `ActionRetryableException` stays in the
  `ActionProcessor`.

## Architecture

```
ActionProcessor.process(envelope)
      │  leadReadPort.findLead(leadId)
      ▼
LeadReadPort (interface, unchanged)
      │
      ├── profile local/test/default ──▶ StubLeadReadPort (unchanged, in-memory)
      │
      └── profile !local (runtime)    ──▶ HttpLeadReadPort  ──HTTP GET──▶ storage-manager-api
                                             (RestClient: base URL +        /api/v1/leads/{id}
                                              timeouts + optional s2s bearer)
```

## Components and Interfaces

### 1. `HttpLeadReadPort` (new) — `action.lead` package

```java
@Component
@Profile("!local & !test & !default")   // active only in the non-local runtime profile
public class HttpLeadReadPort implements LeadReadPort {

    private final RestClient restClient;        // api base URL + timeouts + optional s2s bearer

    @Override
    public Optional<LeadView> findLead(UUID leadId) {
        Objects.requireNonNull(leadId, "leadId must not be null");
        try {
            LeadReadResponse body = restClient.get()
                    .uri("/api/v1/leads/{id}", leadId)
                    .retrieve()
                    .body(LeadReadResponse.class);
            return Optional.of(toLeadView(body));           // Req 1.3, 2.*
        } catch (HttpClientErrorException.NotFound nf) {
            return Optional.empty();                         // Req 3.1 — terminal not-found
        } catch (RestClientResponseException e) {
            // 5xx, 401, 403, other statuses -> retryable with a status-bearing detail (Req 3.3, 3.5)
            throw new LeadReadException("lead read failed (" + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            // transport error / unparseable body -> retryable (Req 3.4)
            throw new LeadReadException("lead read transport error: " + e.getMessage(), e);
        }
    }
}
```

- Registered under `@Profile("!local & !test & !default")` so it is the single
  active `LeadReadPort` in the runtime profile, and the stub stays active for
  `local`/`test`/`default` (Req 5.1, 5.2). (The stub is `@Profile({"local","test","default"})`,
  so the two never coexist.)
- Catches `HttpClientErrorException.NotFound` for 404 → `Optional.empty()`; all
  other `RestClientResponseException` (5xx/401/403/other) and `RestClientException`
  (transport) → `LeadReadException` (Req 3).
- Maps to `LeadView` (see below). A 200 whose body is null/unparseable or missing a
  required field (`organizationId`) → `LeadReadException` (Req 2.1, 3.4).

### 2. `LeadReadResponse` (new) — a minimal read DTO

A local, tolerant deserialization record for exactly the fields the adapter needs,
annotated `@JsonIgnoreProperties(ignoreUnknown = true)` so the rest of the api's
`LeadDetailResponse` (names, addresses, timestamps, etc.) is ignored:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
record LeadReadResponse(
        UUID organizationId,          // A2
        UUID facilityId,
        String email,
        String mobilePhone,
        ContactMethodDto preferredContactMethod,   // EMAIL | SMS
        Map<String, Object> properties             // A3; may be absent -> null -> empty map
) {}
```

- `ContactMethodDto` is a small local enum `{ EMAIL, SMS }` (the adapter does not
  depend on the api's enum type); it is translated to the communications
  `MessageType` by name.

### 3. Response → `LeadView` mapping

| `LeadView` field  | Source                                   | Rule                                                        |
|-------------------|------------------------------------------|-------------------------------------------------------------|
| `organizationId`  | `organizationId` (A2)                    | required; null on a 200 → `LeadReadException` (Req 2.1)      |
| `facilityId`      | `facilityId`                             | passed through (null tolerated by `LeadView`)               |
| `email`           | `email`                                  | passed through                                              |
| `phone`           | `mobilePhone`                            | the SMS contact (Req 2.4)                                   |
| `preferredMethod` | `preferredContactMethod`                 | `ContactMethod`→`MessageType` by name (Req 2.5)             |
| `properties`      | `properties` (A3)                        | opaque; absent → `Map.of()` (never null) (Req 2.6, 2.7)     |

### 4. Configuration — `config` package (mirrors the events direction)

**`ApiReadProperties`** (`@ConfigurationProperties("storagemanager.api")`):

- `baseUrl: String` — e.g. `http://localhost:8080` (Req 4.1).
- `connectTimeout` / `readTimeout: Duration` — applied to the request factory
  (Req 4.2), via a `clientRequestFactoryWithTimeouts()` helper exactly like
  `EventsIngestProperties`.
- `auth.enabled: boolean` (default `false`) — the Phase-0 s2s toggle (Req 4.3).

**`ApiAuthTokenProvider`** (seam interface + no-op default bean):

- `String currentToken()` — returns the `leads/read`-scoped bearer token, or
  `null` when disabled/unavailable (Req 4.4). A `Disabled` no-op `@Bean` returns
  `null`. A real client-credentials implementation (mirroring the api-side
  `ClientCredentialsEventsAuthTokenProvider`) replaces it later; out of scope (A1).

**`ApiReadTransportConfig`** (`@Configuration`, `@EnableConfigurationProperties(ApiReadProperties.class)`):

- Builds the `RestClient` from the properties (`baseUrl` + timeout request
  factory); when `auth.enabled`, adds a request interceptor attaching
  `setBearerAuth(tokenProvider.currentToken())`; otherwise none (Req 4.3).
- Fails fast when `baseUrl` is blank (Req 4.1).
- Declares the `HttpLeadReadPort` bean wired with that `RestClient`, under the same
  `@Profile("!local & !test & !default")` guard (so the bean and its client are
  only created in the runtime profile; local/test contexts don't need the api
  base URL).

### Configuration keys (new, in `application.properties`)

```properties
# storage-manager-api read transport (live LeadReadPort). Mirrors the
# storagemanager.messaging.events.* convention, pointing at the api.
storagemanager.api.base-url=${API_BASE_URL:http://localhost:8080}
storagemanager.api.connect-timeout=2s
storagemanager.api.read-timeout=5s
storagemanager.api.auth.enabled=false
```

## Data Models

This feature introduces **no new persistent entity, table, or migration**. The
only new models are the read DTO (`LeadReadResponse` + `ContactMethodDto`) and the
configuration properties (`ApiReadProperties`). The existing `LeadView`,
`LeadReadPort`, and `LeadReadException` are reused unchanged.

### Configuration model — `ApiReadProperties` (`storagemanager.api.*`)

| Property          | Type       | Default                   | Purpose                                  |
|-------------------|------------|---------------------------|------------------------------------------|
| `base-url`        | `String`   | (required; local default) | storage-manager-api base URL             |
| `connect-timeout` | `Duration` | `2s`                      | Outbound HTTP connect timeout            |
| `read-timeout`    | `Duration` | `5s`                      | Outbound HTTP read timeout               |
| `auth.enabled`    | `boolean`  | `false`                   | Toggle s2s bearer-token attachment       |

## Correctness properties

1. **Property 1 — Response→LeadView mapping is faithful.** For any well-formed 200
   lead response, `findLead` returns a `LeadView` whose fields equal the mapped
   source fields: `organizationId`, `facilityId`, `email`, `phone == mobilePhone`,
   `preferredMethod == MessageType.valueOf(preferredContactMethod.name())`, and
   `properties` equal to the response map (empty when absent). _Validates: Req
   2.1–2.7._
2. **Property 2 — Outcome mapping is total.** For any HTTP outcome, `findLead`
   yields exactly one result: 404 → `Optional.empty()`; 2xx with a valid body →
   populated `Optional`; 5xx / 401 / 403 / transport error / unparseable body /
   missing `organizationId` → `LeadReadException`. It never returns null and never
   throws an unmapped exception. _Validates: Req 3.1–3.6, 2.1._
3. **Property 3 — Auth attachment follows the toggle.** When `auth.enabled`, every
   outbound request carries the provider's bearer token; when disabled, no
   `Authorization` header is sent and the provider is never consulted. _Validates:
   Req 4.3, 4.4._

## Error handling

| Condition                                   | Adapter result                 | ActionProcessor effect                 |
|---------------------------------------------|--------------------------------|----------------------------------------|
| 200 + valid body                            | `Optional.of(LeadView)`        | proceeds                               |
| 404                                         | `Optional.empty()`             | terminal "lead not found" rejection    |
| 5xx                                         | throw `LeadReadException`      | retryable (redeliver)                  |
| 401 / 403                                   | throw `LeadReadException`      | retryable, with status in the detail   |
| transport error / timeout                   | throw `LeadReadException`      | retryable                              |
| 200 but unparseable / missing organizationId| throw `LeadReadException`      | retryable                              |

No new exception types beyond the existing `LeadReadException`. The adapter catches
`RestClient*` exceptions and converts them; the `ActionProcessor`'s existing
mapping (empty → terminal, `LeadReadException` → retryable) is unchanged.

## Testing strategy

- **Property tests (jqwik, `@Property(tries = 100)`)**, each tagged
  `Feature: live-lead-read-port, Property N: <text>`:
  - P1 mapping: drive `HttpLeadReadPort` against a `RestClient` bound to
    `MockRestServiceServer` returning generated 200 bodies; assert the `LeadView`
    field mapping (incl. `phone==mobilePhone`, enum-by-name, empty-properties
    default).
  - P2 outcome totality: script 404 / 2xx / 5xx / 401 / 403 / transport error /
    malformed body and assert `Optional.empty()` vs populated vs `LeadReadException`
    per the table.
  - P3 auth toggle: assert bearer attach/omit and provider-consultation count per
    `auth.enabled`, mirroring the events-direction auth test.
- **Example / context tests:**
  - A context test asserting the active `LeadReadPort` under `test`/`default` is the
    `StubLeadReadPort` (the live adapter is not wired in those profiles), and that
    the `HttpLeadReadPort` bean is created under a simulated runtime profile.
  - An `ApplicationContextRunner` negative test asserting a blank
    `storagemanager.api.base-url` fails fast in the runtime profile.
  - An example test for the 404→empty and 200→populated happy paths.

## Decisions

- **D1 — Profile-gated live adapter, stub unchanged.** The live port activates only
  in the non-local runtime profile; the stub stays the default for local/test, so
  existing tests and local dev are unaffected and exactly one `LeadReadPort` is
  active per profile (Req 5).
- **D2 — Tolerant local read DTO.** The adapter deserializes its own
  `LeadReadResponse` with `@JsonIgnoreProperties(ignoreUnknown = true)` rather than
  depending on the api's `LeadDetailResponse` type, so the two services stay
  decoupled and unrelated api response fields are ignored.
- **D3 — `phone = mobilePhone`.** Per decision, the SMS contact maps from
  `mobilePhone` only (always present on a lead); `homePhone` is not used.
- **D4 — Mirror the events-direction transport.** Config/auth structure follows
  `EventsIngestProperties`/`EventsAuthTokenProvider`/`MessagingTransportConfig`,
  under a new `storagemanager.api.*` group.
- **D5 — External api changes assumed, not made.** `organizationId` and
  `properties` on the response and the s2s read path are assumed (A1–A3) and tracked
  as separate api-side work; this spec changes no api code.
