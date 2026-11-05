/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves the Python source of a pushed tool over {@code GET /api/tools/{name}}.
 *
 * <p>Extracted from {@link FlowTreeApiEndpoint}, which registers each tool's
 * file through {@link FlowTreeApiEndpoint#registerToolFile(String, Path)} and
 * holds the backing map; this handler reads that map to answer the download.
 * Keeping the serving logic in its own type both keeps the endpoint under the
 * per-file line budget and gives the "serve a registered tool file"
 * responsibility a home of its own, next to the other endpoint handlers.</p>
 *
 * @author Michael Murray
 */
public final class ToolDownloadHandler implements ConsoleFeatures {

    /** The registered tool files, keyed by tool server name. */
    private final Map<String, Path> toolFiles;

    /**
     * Creates a handler reading from the endpoint's registry of tool files.
     *
     * @param toolFiles the live map of tool name to source path; shared with
     *                  the endpoint so newly registered tools are served
     *                  without rebuilding the handler
     */
    public ToolDownloadHandler(Map<String, Path> toolFiles) {
        this.toolFiles = toolFiles;
    }

    /**
     * Serves the registered tool's Python source as {@code text/plain}.
     *
     * @param name the tool server name
     * @return the file content, or a 404 response when no file is registered
     *         under {@code name} or it no longer exists on disk
     */
    public Response handle(String name) {
        Path filePath = toolFiles.get(name);
        if (filePath == null || !Files.exists(filePath)) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT, "Tool not found: " + name);
        }

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            log("Served pushed tool: " + name + " (" + content.length() + " bytes)");
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                    NanoHTTPD.MIME_PLAINTEXT, content);
        } catch (IOException e) {
            warn("Failed to read tool file " + name + ": " + e.getMessage());
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT, "Failed to read tool file");
        }
    }
}
