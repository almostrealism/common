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
import fi.iki.elonen.NanoHTTPD.ResponseException;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.SmsComplianceProgram;
import org.almostrealism.util.SmsComplianceProgram.Keyword;
import org.almostrealism.util.SmsOptOutRegistry;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles the SignalWire inbound-message webhook, applying the registered
 * keyword rules so a recipient can unsubscribe, resubscribe, or ask for help by
 * text.
 *
 * <p>SignalWire delivers an inbound SMS as a form-encoded {@code POST} carrying
 * {@code From}, {@code To}, and {@code Body}, and expects a cXML reply —
 * {@code <Response><Message>...</Message></Response>} to text the sender back,
 * or an empty {@code <Response/>} to say nothing. The webhook is served at
 * {@value #PATH}; point the toll-free number's inbound messaging setting there.</p>
 *
 * <p>The keyword classification and reply wording are not this handler's to
 * decide: they are the carrier-registered contract owned by
 * {@link SmsComplianceProgram}. This handler's own job is the side effect —
 * recording the opt-out or clearing it in the {@link SmsOptOutRegistry} — and
 * turning the chosen reply into cXML.</p>
 *
 * <p><strong>TODO (request signature):</strong> SignalWire signs each webhook
 * with an {@code X-SignalWire-Signature} header computed from the account's
 * auth token and the exact public request URL. The token is available to the
 * controller (it is the same {@code sw.token} the delivery provider uses), but
 * validating the signature additionally requires the exact externally-visible
 * URL the number is pointed at, which the controller does not reliably know
 * behind a reverse proxy. Validation is therefore left unimplemented rather
 * than added with a guessed URL that would reject legitimate traffic; wire the
 * public URL through configuration and verify the HMAC here before exposing the
 * endpoint on the public internet.</p>
 *
 * @author Michael Murray
 * @see SmsComplianceProgram
 * @see SmsOptOutRegistry
 */
public final class InboundSmsHandler implements ConsoleFeatures {

    /** The path the SignalWire number's inbound messaging webhook is pointed at. */
    public static final String PATH = "/api/sms/inbound";

    /** The opt-out record updated when a sender texts STOP or START. */
    private final SmsOptOutRegistry optOut;

    /**
     * Creates a handler that records consent changes in the given registry.
     *
     * @param optOut the opt-out record to update
     */
    public InboundSmsHandler(SmsOptOutRegistry optOut) {
        this.optOut = optOut;
    }

    /**
     * Handles an inbound-message webhook request, returning the cXML reply.
     *
     * @param session the HTTP session carrying the form-encoded message
     * @return a cXML response
     */
    Response handle(IHTTPSession session) {
        Map<String, String> form = parseForm(session);
        String from = form.get("From");
        String body = form.get("Body");

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/xml",
                respond(from, body));
    }

    /**
     * Applies the keyword rules to one inbound message and returns the cXML
     * reply document, performing the consent side effect along the way.
     *
     * <p>Separated from {@link #handle(IHTTPSession)} so the keyword behaviour
     * and the cXML it produces can be exercised without a live HTTP session.</p>
     *
     * @param from the sender's number in E.164 form
     * @param body the message body
     * @return the cXML response document
     */
    String respond(String from, String body) {
        return cxml(reply(from, body));
    }

    /**
     * Applies the registered keyword rules to one inbound message, performing
     * the consent side effect and returning the reply text.
     *
     * <p>Separated from {@link #handle(IHTTPSession)} so the keyword behaviour
     * can be exercised without a live HTTP session.</p>
     *
     * @param from the sender's number in E.164 form
     * @param body the message body
     * @return the reply text, or {@code null} when no reply is owed
     */
    String reply(String from, String body) {
        Keyword keyword = SmsComplianceProgram.classifyInbound(body);

        if (keyword == Keyword.STOP && optOut != null) {
            optOut.optOut(from);
        } else if (keyword == Keyword.START && optOut != null) {
            optOut.optIn(from);
        }

        return SmsComplianceProgram.replyFor(keyword);
    }

    /**
     * Reads the form-encoded parameters from the request, tolerating either the
     * parsed-parameter shape NanoHTTPD produces for
     * {@code application/x-www-form-urlencoded} or a raw body it leaves under
     * {@code postData}.
     *
     * @param session the HTTP session
     * @return the decoded form parameters, never {@code null}
     */
    private Map<String, String> parseForm(IHTTPSession session) {
        Map<String, String> params = new HashMap<>();
        if (session == null) return params;

        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            if (session.getParms() != null) params.putAll(session.getParms());
            if (params.isEmpty() && files.get("postData") != null) {
                decodeInto(files.get("postData"), params);
            }
        } catch (IOException | ResponseException e) {
            warn("Error reading inbound SMS body: " + e.getMessage());
        }

        return params;
    }

    /**
     * Decodes a {@code application/x-www-form-urlencoded} body into the given
     * map, so a raw {@code postData} body is read the same way parsed
     * parameters would have been.
     *
     * @param encoded the url-encoded body
     * @param into    the map to populate
     */
    private void decodeInto(String encoded, Map<String, String> into) {
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            into.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
    }

    /**
     * Wraps reply text as a cXML document, escaping it for XML. A {@code null}
     * reply becomes the empty {@code <Response/>} that tells SignalWire to send
     * nothing back.
     *
     * @param message the reply text, or {@code null} for no reply
     * @return the cXML document
     */
    private String cxml(String message) {
        if (message == null) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response/>";
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Message>"
                + escapeXml(message) + "</Message></Response>";
    }

    /**
     * Escapes the five XML metacharacters so a reply containing {@code &} (as
     * the registered replies do, in "Msg&amp;data") is well-formed cXML.
     *
     * @param text the text to escape
     * @return the XML-escaped text
     */
    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
