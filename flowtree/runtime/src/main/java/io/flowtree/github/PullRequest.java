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

package io.flowtree.github;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A pull request as GitHub's REST API renders it in a pull-request listing.
 *
 * <p>GitHub reports a merged pull request as {@code state: "closed"} with a
 * {@code merged_at} time, so "closed" alone does not say whether the work
 * landed. This type answers that question in one place.</p>
 *
 * @param json one element of a {@code GET /repos/{owner}/{repo}/pulls} response
 */
public record PullRequest(JsonNode json) {

    /**
     * Reports whether the pull request was merged.
     *
     * @return {@code true} when the pull request has a merge time
     */
    public boolean isMerged() {
        JsonNode mergedAt = json.path("merged_at");
        return !mergedAt.isMissingNode() && !mergedAt.isNull() && !mergedAt.asText().isEmpty();
    }

    /**
     * Reports whether the pull request is still open.
     *
     * @return {@code true} when GitHub reports the state {@code open}
     */
    public boolean isOpen() {
        return "open".equals(json.path("state").asText(""));
    }

    /**
     * Reports whether the pull request was closed without being merged.
     *
     * @return {@code true} when it is closed and has no merge time
     */
    public boolean isClosedUnmerged() {
        return "closed".equals(json.path("state").asText("")) && !isMerged();
    }

    /**
     * Returns the pull request's number.
     *
     * @return the number, or {@code 0} when the listing omits it
     */
    public int number() {
        return json.path("number").asInt();
    }
}
