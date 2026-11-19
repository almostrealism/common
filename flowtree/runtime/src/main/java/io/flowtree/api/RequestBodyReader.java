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

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.ResponseException;
import io.flowtree.github.GitHubProxyHandler;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the raw request body of a NanoHTTPD session, whatever the HTTP method.
 *
 * <p>NanoHTTPD's {@code parseBody} keeps a POST body inline under
 * {@code postData} and a PUT body in a temp file named under {@code content},
 * but discards the body of any other method. A PATCH body is therefore read
 * straight from the session's input stream, bounded by its
 * {@code content-length}.</p>
 */
public class RequestBodyReader implements GitHubProxyHandler.BodyReader, ConsoleFeatures {

    /**
     * The largest request body the reader will read into memory. A pull
     * request body is at most tens of kilobytes, so a few megabytes is
     * generous while still bounding the buffer a client's {@code Content-Length}
     * can force this to allocate — without a bound a declared length of up to
     * {@link Integer#MAX_VALUE} would be allocated before the body reaches
     * GitHub, an avoidable memory-exhaustion path.
     */
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    /**
     * Reads the request body from the given session.
     *
     * <p>A PATCH body that cannot be read in full — a bad {@code Content-Length},
     * a truncated or failed stream read — is rejected with an
     * {@link InvalidRequestBodyException} rather than returned as {@code null}.
     * {@link GitHubProxyHandler#handle} treats {@code null} as an empty body and
     * still forwards it, so returning {@code null} on a read failure would turn a
     * failed read into a PATCH that silently clears the pull request's fields.
     * A failed POST/PUT read keeps the previous {@code null} behaviour.</p>
     *
     * @param session the NanoHTTPD session
     * @return the body string, or {@code null} when there is none or a non-PATCH body cannot be read
     * @throws InvalidRequestBodyException when a PATCH declares a
     *         {@code Content-Length} that is missing, unparseable, negative, or
     *         above {@link #MAX_BODY_BYTES}, yields fewer bytes than declared, or
     *         cannot be read from the stream
     */
    @Override
    public String read(IHTTPSession session) {
        if (Method.PATCH.equals(session.getMethod())) {
            try {
                return readPatchBody(session);
            } catch (IOException e) {
                // Rejected so the handler returns a 4xx rather than forwarding
                // a failed read as an empty PATCH.
                throw new InvalidRequestBodyException(
                        "could not read PATCH request body: " + e.getMessage());
            }
        }
        try {
            Map<String, String> bodyMap = new HashMap<>();
            session.parseBody(bodyMap);
            if (bodyMap.containsKey("postData")) {
                return bodyMap.get("postData");
            }
            String contentFile = bodyMap.get("content");
            if (contentFile != null && !contentFile.isEmpty()) {
                return Files.readString(Path.of(contentFile), StandardCharsets.UTF_8);
            }
            return null;
        } catch (IOException | ResponseException e) {
            warn("Error reading body: " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads a PATCH body from the session's input stream, bounded by a
     * validated {@code Content-Length}.
     *
     * <p>NanoHTTPD discards a PATCH body, so it is read straight from the
     * stream. The declared length is checked against {@link #MAX_BODY_BYTES}
     * <em>before</em> any buffer is allocated: a missing, unparseable,
     * negative, or over-limit length is rejected with an
     * {@link InvalidRequestBodyException} rather than trusted to size the
     * allocation. A stream that ends before the declared length is read — a
     * client that closed early — is likewise rejected rather than forwarded as
     * a truncated body.</p>
     *
     * @param session the NanoHTTPD session
     * @return the body string
     * @throws IOException                 if the stream cannot be read
     * @throws InvalidRequestBodyException if the declared length is absent or out of range,
     *         or the stream yields fewer bytes than declared
     */
    private String readPatchBody(IHTTPSession session) throws IOException {
        String length = session.getHeaders().get("content-length");
        if (length == null || length.isBlank()) {
            throw new InvalidRequestBodyException(
                    "a PATCH request requires a Content-Length header");
        }
        long declared;
        try {
            declared = Long.parseLong(length.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestBodyException(
                    "unparseable Content-Length header: " + length.trim());
        }
        if (declared < 0 || declared > MAX_BODY_BYTES) {
            throw new InvalidRequestBodyException(
                    "Content-Length " + declared + " is outside the accepted range 0.."
                            + MAX_BODY_BYTES + " bytes");
        }
        byte[] bytes = session.getInputStream().readNBytes((int) declared);
        if (bytes.length != declared) {
            throw new InvalidRequestBodyException(
                    "truncated request body: read " + bytes.length + " of "
                            + declared + " declared bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
