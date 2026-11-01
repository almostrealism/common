/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import org.almostrealism.io.AlertRecipients;
import org.almostrealism.io.RateLimit;
import org.almostrealism.util.SignalWireDeliveryProvider;
import org.almostrealism.util.SmsOptOutRegistry;

import java.util.Map;
import java.util.function.Function;

/**
 * The SMS alerting surface of {@link FlowTreeApiEndpoint}: the outbound
 * {@code POST /api/alerts} route and the inbound SignalWire webhook, together
 * with the recipient directory and opt-out state they share.
 *
 * <p>These are one cohesive concern — the two directions of the same
 * carrier-registered messaging program — so they live together here rather than
 * inline in the endpoint. The endpoint owns routing and delegates the handling;
 * this type owns the handler lifecycle so a recipient or opt-out change rebuilds
 * the alert handler in one place.</p>
 *
 * @author Michael Murray
 * @see AlertRequestHandler
 * @see InboundSmsHandler
 */
final class AlertEndpoints {

    /** Reads the POST body from a session; supplied by the owning endpoint. */
    private final Function<IHTTPSession, String> readBody;

    /** The named recipient directory. Starts empty until configuration loads. */
    private AlertRecipients recipients = new AlertRecipients();

    /** Handle → destination number, for opt-out lookup; empty until configured. */
    private Map<String, String> recipientNumbers = Map.of();

    /** The opt-out record shared by the alert handler and the inbound webhook. */
    private SmsOptOutRegistry optOut;

    /** Serves {@code POST /api/alerts}; rebuilt when recipients or opt-out change. */
    private AlertRequestHandler alertHandler;

    /** Serves the inbound webhook; null until an opt-out record is set. */
    private InboundSmsHandler inboundSmsHandler;

    /**
     * Creates the surface with an empty recipient directory, so the alert route
     * answers coherently ("no recipients are configured") before configuration
     * loads rather than failing as an unknown route.
     *
     * @param readBody body reader supplied by the owning endpoint
     */
    AlertEndpoints(Function<IHTTPSession, String> readBody) {
        this.readBody = readBody;
        rebuildAlertHandler();
    }

    /**
     * Sets the named recipients reachable through {@code POST /api/alerts}.
     *
     * @param recipients the recipient directory built from configuration
     */
    void setRecipients(AlertRecipients recipients) {
        this.recipients = recipients == null ? new AlertRecipients() : recipients;
        rebuildAlertHandler();
    }

    /**
     * Configures SMS compliance: the handle → number map used to recognise an
     * opted-out recipient, and the opt-out record shared by the alert handler
     * and the inbound webhook.
     *
     * @param numbers handle → destination number, or {@code null} for none
     * @param optOut  the opt-out record, or {@code null} to disable opt-out
     */
    void setCompliance(Map<String, String> numbers, SmsOptOutRegistry optOut) {
        this.recipientNumbers = numbers == null ? Map.of() : numbers;
        this.optOut = optOut;
        this.inboundSmsHandler = optOut == null ? null : new InboundSmsHandler(optOut);
        rebuildAlertHandler();
    }

    /**
     * Handles {@code POST /api/alerts}.
     *
     * @param session the HTTP session
     * @return the alert delivery response
     */
    Response handleAlert(IHTTPSession session) {
        return alertHandler.handle(session);
    }

    /**
     * Handles the inbound SMS webhook, or reports that it is not configured when
     * no opt-out record has been wired.
     *
     * @param session the HTTP session
     * @return the cXML response, or a 503 when inbound SMS is unconfigured
     */
    Response handleInbound(IHTTPSession session) {
        if (inboundSmsHandler == null) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    "{\"ok\":false,\"error\":\"inbound SMS is not configured\"}");
        }
        return inboundSmsHandler.handle(session);
    }

    /** Rebuilds the alert handler from the current recipients and opt-out state. */
    private void rebuildAlertHandler() {
        this.alertHandler = new AlertRequestHandler(recipients,
                callerAlertLimit(), readBody, recipientNumbers, optOut);
    }

    /**
     * Returns the per-caller alert budget: half the delivery provider's
     * account-wide ceiling, over the same window.
     *
     * <p>Deriving it from the provider's own limit rather than restating a
     * number keeps the two from drifting apart, and makes the intent legible —
     * no single caller may spend more than half of what the account can
     * afford.</p>
     *
     * @return the per-caller rate limit
     */
    private static RateLimit callerAlertLimit() {
        return new RateLimit(Math.max(1, SignalWireDeliveryProvider.messageLimit() / 2),
                SignalWireDeliveryProvider.limitWindow());
    }
}
