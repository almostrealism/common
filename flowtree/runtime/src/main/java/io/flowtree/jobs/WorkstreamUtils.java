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

/**
 * Shared utilities for working with workstream resources.
 *
 * <p>Package-private.</p>
 */
final class WorkstreamUtils {

    /** Static-only helper. */
    private WorkstreamUtils() {}

    /**
     * Extracts the workstream id from a workstream URL of the shape
     * {@code .../api/workstreams/<wsId>[/jobs/...]}.
     *
     * @param url the workstream URL or {@code null}
     * @return the workstream id, or {@code null} when none could be parsed
     */
    static String extractWorkstreamId(String url) {
        if (url == null || url.isEmpty()) return null;
        int idx = url.indexOf("/workstreams/");
        if (idx < 0) return null;
        int start = idx + "/workstreams/".length();
        int end = url.indexOf('/', start);
        String id = end < 0 ? url.substring(start) : url.substring(start, end);
        return id.isEmpty() ? null : id;
    }
}