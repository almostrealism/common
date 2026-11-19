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
     * Reads the request body from the given session.
     *
     * @param session the NanoHTTPD session
     * @return the body string, or {@code null} when there is none or it cannot be read
     */
    @Override
    public String read(IHTTPSession session) {
        try {
            if (Method.PATCH.equals(session.getMethod())) {
                String length = session.getHeaders().get("content-length");
                if (length == null || length.isEmpty()) {
                    return null;
                }
                byte[] bytes = session.getInputStream().readNBytes(Integer.parseInt(length.trim()));
                return new String(bytes, StandardCharsets.UTF_8);
            }
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
        } catch (IOException | ResponseException | IllegalArgumentException e) {
            warn("Error reading body: " + e.getMessage());
            return null;
        }
    }
}
