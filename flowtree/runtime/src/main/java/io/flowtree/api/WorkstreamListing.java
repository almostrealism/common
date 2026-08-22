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

import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import io.flowtree.jobs.GitOperations;
import io.flowtree.workstream.Workstream;

/**
 * Renders the workstream listing, applying the caller's filters.
 *
 * <p>Separate from {@link FlowTreeApiEndpoint} because the filtering is the
 * substance of the endpoint rather than part of its request routing, and
 * because the endpoint sits at the file-length limit.</p>
 */
final class WorkstreamListing {

    /** Not instantiable: this type is a namespace for the renderer below. */
    private WorkstreamListing() { }

    /**
     * Renders {@code GET /api/workstreams} as a JSON array of registered
     * workstreams with their configuration and capabilities.
     *
     * <p>Accepts optional filters, applied here rather than by the caller so
     * that "which workstreams match this predicate?" costs one request instead
     * of a full listing plus a scan:</p>
     *
     * <ul>
     *   <li>{@code workspaceId} — exact match on the owning workspace.</li>
     *   <li>{@code repoUrl} — matched on repository identity, so the SSH and
     *       HTTPS spellings of one repository are equivalent.</li>
     *   <li>{@code dispatchCapable} — {@code true} or {@code false}.</li>
     *   <li>{@code archived} — {@code true} for archived only, {@code false}
     *       for live only. Supersedes {@code includeArchived}, the older and
     *       coarser parameter, which is still honoured when {@code archived}
     *       is absent.</li>
     * </ul>
     *
     * <p>An empty parameter value counts as absent, so a caller assembling a
     * query from optional values need not omit the keys it has no value for.</p>
     *
     * @param session     the request, whose query parameters carry the filters
     * @param workstreams every registered workstream, keyed by id
     * @return a JSON array of the workstreams that pass every filter
     */
static String toJson(IHTTPSession session, Map<String, Workstream> workstreams) {
        String workspaceId = RequestParameters.first(session, "workspaceId", null);
        String repoUrl = RequestParameters.first(session, "repoUrl", null);
        String dispatchCapable = RequestParameters.first(session, "dispatchCapable", null);
        String archived = RequestParameters.first(session, "archived", null);
        boolean includeArchived = "true".equalsIgnoreCase(
                RequestParameters.first(session, "includeArchived", "false"));

        // `archived` is the explicit selector; `includeArchived` is the older
        // parameter it generalises. Both are honoured: archived=true means
        // "only archived", archived=false means "only live", and neither means
        // "live unless includeArchived says otherwise".
        Boolean archivedFilter = archived == null ? null : Boolean.valueOf(
                "true".equalsIgnoreCase(archived));

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Workstream ws : workstreams.values()) {
            if (archivedFilter != null) {
                if (ws.isArchived() != archivedFilter) continue;
            } else if (!includeArchived && ws.isArchived()) {
                continue;
            }
            if (workspaceId != null && !workspaceId.equals(ws.getWorkspaceId())) continue;
            if (repoUrl != null && !GitOperations.isSameRepository(repoUrl, ws.getRepoUrl())) {
                continue;
            }
            if (dispatchCapable != null
                    && ws.isDispatchCapable() != "true".equalsIgnoreCase(dispatchCapable)) {
                continue;
            }
            if (!first) json.append(",");
            first = false;
            json.append(ws.toSummaryJson());
        }
        json.append("]");
        return json.toString();
    }
}
