# Implementation Plan: Live Lead Read Port

## Overview

This plan implements a live `LeadReadPort` in the `storage-manager-communications`
service that reads a single lead from `storage-manager-api`
(`GET /api/v1/leads/{id}`) over HTTP and projects the response onto the existing
`LeadView`. The live adapter (`HttpLeadReadPort`) activates only in the non-local
runtime profile; `StubLeadReadPort` remains the implementation for `local`,
`test`, and `default`. The HTTP client, timeouts, and s2s auth seam mirror the
existing comms→events outbound transport.

No storage-manager-api code is changed. The adapter is written assuming the api
exposes (A1) an s2s/scope read path, (A2) `organizationId` on the lead response,
and (A3) a `properties` map — tracked as separate api-side work.

New types live under `com.storagemanager.communications`:
`action/lead/HttpLeadReadPort`, `action/lead/LeadReadResponse` (+ a local
`ContactMethodDto`), `config/ApiReadProperties`, `config/ApiAuthTokenProvider`,
`config/ApiReadTransportConfig`. The `LeadReadPort` / `LeadView` /
`LeadReadException` contracts are unchanged.

Property-based tests use jqwik (`@Property(tries = 100)`), one per correctness
property, each tagged `Feature: live-lead-read-port, Property N: <text>`.

## Tasks

- [x] 1. Add api-connection configuration (properties + auth seam)
  - [x] 1.1 Create `ApiReadProperties`
    - Add `@ConfigurationProperties("storagemanager.api")` with `baseUrl: String`,
      `connectTimeout: Duration`, `readTimeout: Duration`, and a nested
      `Auth { boolean enabled = false }`; add a `clientRequestFactoryWithTimeouts()`
      helper mirroring the comms-side `EventsIngestProperties`
    - _Requirements: 4.1, 4.2, 4.5, 3.? (timeouts feed 3.4)_
    - _Design: Configuration (ApiReadProperties); Data Models_

  - [x] 1.2 Create `ApiAuthTokenProvider` seam + no-op default
    - Add the `ApiAuthTokenProvider` interface with `String currentToken()` and a
      `Disabled` no-op implementation returning `null`, mirroring the comms-side
      `EventsAuthTokenProvider`; document that it returns a `leads/read`-scoped
      service token when enabled (real token production is out of scope, A1)
    - _Requirements: 4.3, 4.4_
    - _Design: Configuration (ApiAuthTokenProvider)_

- [x] 2. Implement the read DTO and the HTTP adapter
  - [x] 2.1 Create `LeadReadResponse` (+ `ContactMethodDto`)
    - Add a tolerant deserialization record `@JsonIgnoreProperties(ignoreUnknown = true)`
      with `organizationId: UUID`, `facilityId: UUID`, `email: String`,
      `mobilePhone: String`, `preferredContactMethod: ContactMethodDto`,
      `properties: Map<String,Object>`; add a local enum `ContactMethodDto { EMAIL, SMS }`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_
    - _Design: Components (LeadReadResponse); Response → LeadView mapping_

  - [x] 2.2 Create `HttpLeadReadPort implements LeadReadPort`
    - Register `@Component @Profile("!local & !test & !default")`; constructor-inject
      the api `RestClient`; implement `findLead(leadId)` to
      `GET /api/v1/leads/{id}`, parse `LeadReadResponse`, and map to `LeadView`:
      `organizationId` (required — null on 200 → `LeadReadException`), `facilityId`,
      `email`, `phone = mobilePhone`, `preferredMethod` =
      `MessageType.valueOf(preferredContactMethod.name())`, `properties` (empty map
      when absent, opaque passthrough)
    - Map outcomes: 404 → `Optional.empty()`; 2xx valid body → `Optional.of(view)`;
      5xx / 401 / 403 / other status → `LeadReadException` (status in detail);
      transport error / unparseable body / missing `organizationId` →
      `LeadReadException`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_
    - _Design: Components (HttpLeadReadPort); Error handling_

  - [x] 2.3 Create `ApiReadTransportConfig` wiring the RestClient + adapter bean
    - `@Configuration @EnableConfigurationProperties(ApiReadProperties.class)`,
      guarded `@Profile("!local & !test & !default")`; build the `RestClient`
      (`baseUrl` + timeout request factory); when `auth.enabled`, add a request
      interceptor attaching `setBearerAuth(tokenProvider.currentToken())`, else
      none; fail fast on blank `base-url`; declare the api `RestClient` bean (e.g.
      `apiReadRestClient`) used by `HttpLeadReadPort`
    - _Requirements: 4.1, 4.2, 4.3, 5.1_
    - _Design: Configuration (ApiReadTransportConfig)_

- [x] 3. Add configuration defaults and checkpoint
  - [x] 3.1 Add the `storagemanager.api.*` block to `application.properties`
    - Add `base-url` (env-overridable, local default `http://localhost:8080`),
      `connect-timeout`, `read-timeout`, and `auth.enabled=false`
    - _Requirements: 4.1, 4.2, 4.5_

  - [x] 3.2 Checkpoint - compile and ensure existing tests still pass
    - Run the module build/tests; confirm the module compiles with the new types
      and that the pre-existing suite (including the `lead-communication-action`
      and `inbox-action-dispatch` tests) stays green and that the active
      `LeadReadPort` under `test`/`default` is still `StubLeadReadPort`
    - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Property tests for the adapter
  - [x] 4.1 Write property test for response→LeadView mapping
    - **Feature: live-lead-read-port, Property 1: Response to LeadView mapping is faithful**
    - Drive `HttpLeadReadPort` against a `RestClient` bound to
      `MockRestServiceServer` returning generated well-formed 200 bodies; assert the
      `LeadView` field mapping: `organizationId`, `facilityId`, `email`,
      `phone == mobilePhone`, `preferredMethod ==` enum-by-name, and `properties`
      equal to the response map (empty when absent); min 100 tries
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7**

  - [x] 4.2 Write property test for outcome-mapping totality
    - **Feature: live-lead-read-port, Property 2: Outcome mapping is total**
    - Script each outcome family — 404, 2xx (valid body), 5xx, 401, 403, transport
      error, and a malformed/`organizationId`-missing 200 — and assert exactly one
      result per outcome: `Optional.empty()` for 404; populated `Optional` for a
      valid 200; `LeadReadException` for 5xx/401/403/transport/unparseable/missing
      field; never null, never an unmapped throw; min 100 tries
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 2.1**

  - [x] 4.3 Write property test for auth attachment following the toggle
    - **Feature: live-lead-read-port, Property 3: Auth attachment follows the toggle**
    - With `auth.enabled=true` and a counting stub token provider, assert every
      outbound request carries the expected bearer header; with `auth.enabled=false`,
      assert no `Authorization` header is sent and the provider is never consulted;
      min 100 tries
    - **Validates: Requirements 4.3, 4.4**

- [x] 5. Integration / wiring tests
  - [x] 5.1 Write profile/wiring context test
    - Assert that under the `test`/`default` profile the active `LeadReadPort` bean
      is `StubLeadReadPort` (the live adapter is not wired), and — via an
      `ApplicationContextRunner` simulating the runtime profile — that
      `HttpLeadReadPort` is created and that a blank `storagemanager.api.base-url`
      fails fast
    - _Requirements: 5.1, 5.2, 4.1_

  - [x] 5.2 Write example happy-path/not-found integration test
    - With `MockRestServiceServer` standing in for the api: assert a 200 lead
      response yields a populated `LeadView` with the expected fields, and a 404
      yields `Optional.empty()`; assert a 5xx yields `LeadReadException`
    - _Requirements: 1.1, 1.3, 2.1, 2.4, 2.5, 3.1, 3.2, 3.3_

- [x] 6. Final checkpoint - Build and run the module test suite
  - Run the full `storage-manager-communications` test suite and ensure it is green,
    including the new adapter/config classes and the property and integration tests
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- `StubLeadReadPort` and the `LeadReadPort`/`LeadView`/`LeadReadException` contracts
  are unchanged; the live adapter is additive and profile-gated.
- Configuration mirrors the comms→events `storagemanager.messaging.events.*` group,
  under a new `storagemanager.api.*` group; the s2s token's real production is
  platform-owned and out of scope (a disabled no-op provider is the default).
- Property tests use jqwik at `@Property(tries = 100)`, one per property, each
  tagged `Feature: live-lead-read-port, Property N: <text>`.
- External assumptions A1 (s2s read path), A2 (`organizationId` on the response),
  and A3 (`properties` map) are owned by storage-manager-api and tracked as separate
  work; this spec changes no api code.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1"] },
    { "id": 1, "tasks": ["2.2"] },
    { "id": 2, "tasks": ["2.3"] },
    { "id": 3, "tasks": ["3.1", "3.2"] },
    { "id": 4, "tasks": ["4.1", "4.2", "4.3", "5.1"] },
    { "id": 5, "tasks": ["5.2"] },
    { "id": 6, "tasks": ["6"] }
  ]
}
```
