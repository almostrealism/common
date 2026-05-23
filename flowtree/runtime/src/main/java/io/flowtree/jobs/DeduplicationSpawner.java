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

package io.flowtree.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Consumer;

/**
 * Helper that performs {@code DEDUP_SPAWN} mode follow-up job submission for
 * {@link CodingAgentJob}.
 *
 * <p>Extracted from {@link CodingAgentJob} to keep that file under the
 * 1,600-line checkstyle ceiling and to give the controller-call sequence a
 * focused home that can be tested without standing up a job lifecycle.</p>
 *
 * @author Michael Murray
 */
final class DeduplicationSpawner {

    /** Maximum number of method names included in a single deduplication prompt. */
    static final int MAX_DEDUP_METHODS = 50;

    /** JSON mapper used to build the spawn-request payload. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Static-only helper; not instantiable. */
    private DeduplicationSpawner() {}

    /**
     * Submits a deduplication follow-up job to the supplied workstream URL.
     *
     * <p>Fire-and-forget: any error is logged via {@code warn} and does not
     * affect the calling job's outcome.</p>
     *
     * @param newMethods    method names that may need deduplication
     * @param baseBranch    the configured base branch (e.g. {@code "master"},
     *                      {@code "main"}); threaded into the spawned prompt so
     *                      its diff/log/show commands reference the same ref
     *                      that produced {@code newMethods}
     * @param workstreamUrl the workstream URL of the calling job; {@code null}
     *                      or empty results in a warning and no submission
     * @param postJson      transport callback that POSTs the JSON payload
     * @param log           sink for informational lines
     * @param warn          sink for warning lines
     */
    static void submitSpawnJob(List<String> newMethods, String baseBranch,
                               String workstreamUrl,
                               JsonPoster postJson,
                               Consumer<String> log, Consumer<String> warn) {
        if (newMethods.isEmpty()) {
            log.accept("Deduplication scan: no new Java methods detected");
            return;
        }

        log.accept("Deduplication scan: found " + newMethods.size()
                + " new method(s) -- spawning follow-up job");
        List<String> capped = newMethods.size() > MAX_DEDUP_METHODS
                ? newMethods.subList(0, MAX_DEDUP_METHODS) : newMethods;
        boolean truncated = newMethods.size() > MAX_DEDUP_METHODS;
        String prompt = DeduplicationRule.buildDeduplicationPrompt(capped, truncated,
                newMethods.size(), baseBranch);

        if (workstreamUrl == null || workstreamUrl.isEmpty()) {
            warn.accept("Deduplication mode is 'spawn' but no workstream URL is configured -- skipping");
            return;
        }
        String controllerBase = extractControllerBaseUrl(workstreamUrl);
        String workstreamId = extractWorkstreamId(workstreamUrl);
        if (controllerBase == null || workstreamId == null) {
            warn.accept("Cannot parse workstream URL for deduplication job: " + workstreamUrl);
            return;
        }
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("prompt", prompt);
            payload.put("workstreamId", workstreamId);
            payload.put("description", "Deduplication audit: " + newMethods.size() + " new method(s)");
            payload.put("automated", true);
            String json = MAPPER.writeValueAsString(payload);

            log.accept("Spawning deduplication job on workstream " + workstreamId);
            postJson.post(controllerBase + "/api/submit", json);
        } catch (Exception e) {
            warn.accept("Failed to spawn deduplication job: " + e.getMessage());
        }
    }

    /**
     * Extracts the controller base URL (scheme + host + port) from a workstream URL.
     *
     * @param workstreamUrl the full workstream URL
     * @return the controller base URL, or {@code null} when the URL cannot be parsed
     */
    static String extractControllerBaseUrl(String workstreamUrl) {
        int idx = workstreamUrl.indexOf("/api/workstreams/");
        if (idx < 0) return null;
        return workstreamUrl.substring(0, idx);
    }

    /**
     * Extracts the workstream identifier from a workstream URL.
     *
     * @param workstreamUrl the full workstream URL
     * @return the workstream ID, or {@code null} when the URL cannot be parsed
     */
    static String extractWorkstreamId(String workstreamUrl) {
        int start = workstreamUrl.indexOf("/api/workstreams/");
        if (start < 0) return null;
        start += "/api/workstreams/".length();
        int end = workstreamUrl.indexOf("/", start);
        return end < 0 ? workstreamUrl.substring(start) : workstreamUrl.substring(start, end);
    }

    /** Functional interface decoupling the spawner from {@code GitManagedJob.postJson}. */
    @FunctionalInterface
    interface JsonPoster {
        /**
         * POSTs {@code json} to {@code url}.
         *
         * @param url  the target URL
         * @param json the JSON payload
         * @throws Exception when the HTTP call fails
         */
        void post(String url, String json) throws Exception;
    }
}
