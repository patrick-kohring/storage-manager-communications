package com.storagemanager.communications.action.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.Mockito;

import com.storagemanager.communications.model.SendCommunicationRequest;
import com.storagemanager.communications.model.SendStatus;
import com.storagemanager.communications.model.SentCommunication;
import com.storagemanager.communications.service.SendService;

/**
 * Shared counting/capturing send seam for the {@code ActionProcessor} property-test harness
 * (lead-communication-action spec).
 *
 * <p>Wraps a Mockito mock of {@link SendService} and stubs {@link SendService#sendInternal} — the
 * one and only send-seam call site the {@code ActionProcessor} uses (design "Dispatch via the
 * reused send seam"; {@code sendInternal} is public). The stub:
 * <ul>
 *   <li><strong>counts</strong> every invocation of {@code sendInternal(...)} (Req 7.1; the
 *       exactly-once invariant of Property 1 and the zero-send invariant of Property 10);</li>
 *   <li><strong>captures</strong> every {@link SendCommunicationRequest} passed, so tests can assert
 *       the full field mapping (Req 7.2, Property 1);</li>
 *   <li>by default <strong>returns a dummy {@link SentCommunication}</strong> (no DB, no
 *       transaction) echoing the request fields, and</li>
 *   <li>can be configured to <strong>throw</strong> to simulate a dispatch failure (Req 7.5,
 *       Property 9).</li>
 * </ul>
 *
 * <p>Usage from a sibling test:
 * <pre>{@code
 * CountingSendSeam send = new CountingSendSeam();
 * // ... drive the processor with send.mock() ...
 * assertThat(send.invocationCount()).isEqualTo(1);
 * SendCommunicationRequest captured = send.lastRequest();
 * }</pre>
 */
public final class CountingSendSeam {

    private final SendService mock;
    private final AtomicInteger invocationCount = new AtomicInteger();
    private final List<SendCommunicationRequest> capturedRequests = new ArrayList<>();
    private volatile RuntimeException failure;

    public CountingSendSeam() {
        this.mock = Mockito.mock(SendService.class);
        Mockito.when(mock.sendInternal(Mockito.any(SendCommunicationRequest.class)))
                .thenAnswer(invocation -> {
                    SendCommunicationRequest request = invocation.getArgument(0);
                    invocationCount.incrementAndGet();
                    capturedRequests.add(request);
                    if (failure != null) {
                        throw failure;
                    }
                    return dummySentCommunication(request);
                });
    }

    /** @return the Mockito-backed {@link SendService} to inject into the processor. */
    public SendService mock() {
        return mock;
    }

    /** Configures {@code sendInternal(...)} to throw the supplied exception, simulating a dispatch
     * failure (Req 7.5). The invocation is still counted and captured before the throw. */
    public CountingSendSeam failWith(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    /** @return the number of times the send seam was invoked. */
    public int invocationCount() {
        return invocationCount.get();
    }

    /** @return an unmodifiable view of every captured request, in invocation order. */
    public List<SendCommunicationRequest> capturedRequests() {
        return List.copyOf(capturedRequests);
    }

    /** @return the most recently captured request, or {@code null} if the seam was never invoked. */
    public SendCommunicationRequest lastRequest() {
        return capturedRequests.isEmpty() ? null : capturedRequests.get(capturedRequests.size() - 1);
    }

    /** @return the single captured request; fails if the seam was not invoked exactly once. */
    public SendCommunicationRequest singleRequest() {
        if (capturedRequests.size() != 1) {
            throw new IllegalStateException(
                    "expected exactly one send-seam invocation but saw " + capturedRequests.size());
        }
        return capturedRequests.get(0);
    }

    /** Resets the counter, captured requests, and failure configuration. */
    public void reset() {
        invocationCount.set(0);
        capturedRequests.clear();
        failure = null;
        Mockito.clearInvocations(mock);
    }

    /** Builds a dummy DISPATCHED {@link SentCommunication} echoing the request, with no persistence. */
    private static SentCommunication dummySentCommunication(SendCommunicationRequest request) {
        SentCommunication entity = new SentCommunication();
        entity.setId(UUID.randomUUID());
        entity.setPublicId("STUB-" + UUID.randomUUID());
        entity.setFacilityId(request.facilityId());
        entity.setMessageType(request.messageType());
        entity.setMessageContent(request.messageContent());
        entity.setPhoneNumber(request.phoneNumber());
        entity.setEmailAddress(request.emailAddress());
        entity.setEmailSubject(request.emailSubject());
        entity.setRecipientType(request.recipientType());
        entity.setTenantId(request.tenantId());
        entity.setUserId(request.userId());
        entity.setCorrelationId(request.correlationId());
        entity.setOrganizationId(request.organizationId());
        entity.setStatus(SendStatus.DISPATCHED);
        entity.setDateSent(Instant.now());
        return entity;
    }
}
