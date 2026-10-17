package com.storagemanager.communications.inbox;

import org.springframework.stereotype.Component;

import com.storagemanager.communications.action.ActionProcessor;
import com.storagemanager.communications.action.SendCommunicationAction;
import com.storagemanager.communications.messaging.EventEnvelope;

/**
 * The one concrete binding this spec provides (Req 4): an InboxHandler declaring
 * SendCommunicationAction.EVENT_TYPE ("SendCommunication") that delegates to the existing
 * ActionProcessor (Req 4.1, 4.2). It does NOT reimplement validation, recipient resolution,
 * personalization, or the send (Req 4.3), and it does NOT catch the processor's exceptions — the
 * DispatchingHandler maps ActionValidationException/ActionRetryableException centrally. A normal
 * return (an ActionResult from the processor) means the handler completes normally so the
 * DispatchingHandler completes normally (Req 4.4).
 */
@Component
public class SendCommunicationBinding implements InboxHandler {

    private final ActionProcessor actionProcessor;

    public SendCommunicationBinding(ActionProcessor actionProcessor) {
        this.actionProcessor = actionProcessor;
    }

    @Override
    public String eventType() {
        return SendCommunicationAction.EVENT_TYPE; // "SendCommunication" (Req 4.1)
    }

    @Override
    public void handle(EventEnvelope envelope) {
        // Delegate; let ActionValidationException / ActionRetryableException propagate to the
        // DispatchingHandler's central outcome mapping (Req 4.2, 4.3). The returned ActionResult is
        // intentionally ignored here — a normal return is the success signal (Req 4.4).
        actionProcessor.process(envelope);
    }
}
