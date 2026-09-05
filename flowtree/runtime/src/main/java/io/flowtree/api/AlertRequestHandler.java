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
import io.flowtree.JsonFieldExtractor;
import org.almostrealism.io.Alert;
import org.almostrealism.io.AlertRecipients;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.RateLimit;

import java.util.List;
import java.util.function.Function;

/**
 * Handles {@code POST /api/alerts} for {@link FlowTreeApiEndpoint}, delivering
 * free-form text to named recipients.
 *
 * <p>This is the counterpart to the lifecycle alerting that
 * {@link io.flowtree.jobs.JobAlertNotifier} publishes when a job finishes.
 * Both end as an {@link Alert} handed to a delivery provider; they differ only
 * in who composes the text. Here the caller does, which is what lets an
 * interactive agent session send a note without a job existing at all.</p>
 *
 * <p>The request body names recipients by handle, never by address:</p>
 * <pre>{@code
 * {"text": "Build is green", "recipients": ["michael"], "severity": "INFO"}
 * }</pre>
 *
 * <p>Any metadata a caller wants an alert to carry — which workstream, which
 * job — belongs in {@code text}, already formatted, by the time it arrives.
 * The delivery provider receives an {@link Alert} and nothing more, so
 * neither {@code Alert} nor the provider acquires knowledge of flowtree.</p>
 *
 * <h2>Rate limiting</h2>
 * <p>Two budgets apply. The delivery provider enforces the account-wide
 * ceiling that keeps the carrier from treating the account as a spam source.
 * This handler adds a smaller per-caller budget on top, so that one runaway
 * session consumes at most part of the shared ceiling rather than all of it.
 * That is a courtesy, not isolation: callers still share one account, and a
 * determined caller can still degrade delivery for others.</p>
 *
 * @author Michael Murray
 * @see AlertRecipients
 * @see io.flowtree.jobs.JobAlertNotifier
 */
public final class AlertRequestHandler implements ConsoleFeatures {

    /** Maximum accepted length of an alert body, in characters. */
    private static final int maxTextLength = 1000;

    /** The named recipients this handler can reach. */
    private final AlertRecipients recipients;

    /**
     * Per-caller budget. Sized as a fraction of the account-wide ceiling so
     * the two cannot drift apart: raising the provider's limit raises this
     * one with it.
     */
    private final RateLimit callerLimit;

    /** Reads the POST body from a NanoHTTPD session; supplied by the endpoint. */
    private final Function<IHTTPSession, String> readBody;

    /**
     * Creates a handler delivering to the given recipients.
     *
     * @param recipients  the named recipient directory
     * @param callerLimit the per-caller send budget
     * @param readBody    body reader supplied by the parent endpoint
     */
    AlertRequestHandler(AlertRecipients recipients, RateLimit callerLimit,
                        Function<IHTTPSession, String> readBody) {
        this.recipients = recipients;
        this.callerLimit = callerLimit;
        this.readBody = readBody;
    }

    /**
     * Handles {@code POST /api/alerts}.
     *
     * <p>Delivery is reported per recipient rather than as a single verdict:
     * an alert naming three people, one of whom has a stale handle, still
     * reaches the other two and says so.</p>
     *
     * @param session the HTTP session
     * @return a JSON response naming the recipients reached and any that
     *         could not be resolved
     */
    Response handle(IHTTPSession session) {
        String body = readBody.apply(session);

        String text = JsonFieldExtractor.extractString(body, "text");
        if (text == null || text.isBlank()) {
            return error("text is required");
        }
        if (text.length() > maxTextLength) {
            return error("text exceeds " + maxTextLength + " characters");
        }

        List<String> names = JsonFieldExtractor.extractStringArray(body, "recipients");
        if (names == null || names.isEmpty()) {
            return error("recipients is required and must name at least one recipient");
        }

        if (recipients == null || recipients.size() == 0) {
            return error("no alert recipients are configured");
        }

        // The caller identity is advisory: it comes from the calling tool
        // rather than from an authenticated principal, so it buys fairness
        // between well-behaved callers, not security against a hostile one.
        String caller = JsonFieldExtractor.extractString(body, "caller");
        if (caller == null || caller.isBlank()) caller = "anonymous";

        if (!callerLimit.reserve(caller)) {
            log("Alert rate limit reached for caller " + caller);
            return NanoHTTPD.newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS,
                    "application/json",
                    "{\"ok\":false,\"error\":\"rate limit reached: at most "
                            + callerLimit.getMax() + " alerts per "
                            + callerLimit.getWindow().toMinutes()
                            + " minutes per caller\"}");
        }

        Alert alert = new Alert(severity(
                JsonFieldExtractor.extractString(body, "severity")), text);
        List<String> unknown = recipients.send(alert, names);

        log("Alert from " + caller + " sent to " + (names.size() - unknown.size())
                + " of " + names.size() + " recipients");

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                "{\"ok\":true,\"delivered\":" + jsonNames(names, unknown)
                        + ",\"unknown\":" + jsonArray(unknown)
                        + ",\"known\":" + jsonArray(recipients.names()) + "}");
    }

    /**
     * Parses the requested severity, defaulting to {@link Alert.Severity#INFO}
     * for an absent or unrecognised value.
     *
     * @param requested the severity name from the request, or {@code null}
     * @return the severity to attach to the alert
     */
    protected Alert.Severity severity(String requested) {
        if (requested == null || requested.isBlank()) return Alert.Severity.INFO;

        try {
            return Alert.Severity.valueOf(requested.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Alert.Severity.INFO;
        }
    }

    /**
     * Renders the requested names that were not reported unknown.
     *
     * @param names   every requested recipient
     * @param unknown the names that could not be resolved
     * @return a JSON array of the names that were delivered to
     */
    private String jsonNames(List<String> names, List<String> unknown) {
        return jsonArray(names.stream().filter(n -> !unknown.contains(n)).toList());
    }

    /**
     * Renders a list of names as a JSON array.
     *
     * @param values the names to render
     * @return a JSON array string
     */
    private String jsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        for (String value : values) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(JsonFieldExtractor.escapeJson(value)).append("\"");
        }

        return json.append("]").toString();
    }

    /**
     * Builds a 400 response carrying the given message.
     *
     * @param message the error text
     * @return the error response
     */
    private Response error(String message) {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST,
                "application/json",
                "{\"ok\":false,\"error\":\"" + JsonFieldExtractor.escapeJson(message) + "\"}");
    }
}
