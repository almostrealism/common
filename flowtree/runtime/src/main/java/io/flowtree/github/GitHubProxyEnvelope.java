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

package io.flowtree.github;

import io.flowtree.JsonFieldExtractor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The JSON document {@link GitHubProxyHandler} answers a proxied request with:
 * {@code {"status":N,"link":"...","body":<github-json-or-string>}}.
 *
 * <p>The {@code body} member carries GitHub's response in one of three forms:</p>
 * <ul>
 *   <li>a JSON body is spliced in as it stands, so callers receive the structure
 *       GitHub sent;</li>
 *   <li>any other UTF-8 body is a document rather than a structure — job logs and
 *       raw file contents are the cases that occur — and becomes a JSON string,
 *       since splicing text in unquoted would produce an envelope that does not
 *       parse;</li>
 *   <li>a body that is not valid UTF-8 is a file — an Actions artifact archive is the
 *       case that occurs — and decoding it as text would replace its bytes
 *       irrecoverably. It is sent as a base64 string, and the envelope gains
 *       {@code "encoding":"base64"} so the caller knows to decode it.</li>
 * </ul>
 *
 * @param status      the HTTP status GitHub returned
 * @param linkHeader  the pagination {@code Link} header, or {@code null}
 * @param body        the raw response body
 * @param contentType the response {@code Content-Type}, or {@code null} if absent
 */
public record GitHubProxyEnvelope(int status, String linkHeader, byte[] body, String contentType) {

    /**
     * Renders this envelope as JSON.
     *
     * @return the envelope document
     */
    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"status\":").append(status);
        json.append(",\"link\":\"")
            .append(JsonFieldExtractor.escapeJson(linkHeader != null ? linkHeader : ""))
            .append("\"");
        String text = text();
        if (text == null) {
            json.append(",\"encoding\":\"base64\",\"body\":\"")
                .append(Base64.getEncoder().encodeToString(body)).append("\"");
        } else {
            json.append(",\"body\":").append(encodeText(text));
        }
        json.append("}");
        return json.toString();
    }

    /**
     * Renders a text body as the {@code body} member: spliced in when GitHub declared
     * it JSON, quoted otherwise, and {@code null} when empty.
     *
     * @param text the decoded response body
     * @return the body encoded as a JSON value
     */
    private String encodeText(String text) {
        if (text.isEmpty()) return "null";
        if (contentType != null && contentType.contains("json")) return text;

        return "\"" + JsonFieldExtractor.escapeJson(text) + "\"";
    }

    /**
     * Decodes the body as strict UTF-8.
     *
     * @return the decoded text, or {@code null} when the body is not valid UTF-8
     */
    private String text() {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
