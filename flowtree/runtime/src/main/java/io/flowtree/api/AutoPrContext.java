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

/**
 * Context stored at job submission time for auto-creating a pull request
 * when the job completes successfully.
 */
final class AutoPrContext {
    /** URL of the git repository for which a pull request should be created. */
    final String repoUrl;
    /** Base branch against which the pull request will be opened. */
    final String baseBranch;
    /** GitHub organisation name, used to look up the API access token. */
    final String githubOrg;
    /** Human-readable description of the job, used as the PR title/body. */
    final String description;
    /** Whether the pull request should be opened as a draft awaiting approval. */
    final boolean draft;

    /**
     * Constructs a new {@link AutoPrContext}.
     *
     * @param repoUrl     URL of the git repository
     * @param baseBranch  base branch for the pull request
     * @param githubOrg   GitHub organisation name
     * @param description human-readable job description
     * @param draft       whether to open the pull request as a draft
     */
    AutoPrContext(String repoUrl, String baseBranch, String githubOrg, String description,
            boolean draft) {
        this.repoUrl = repoUrl;
        this.baseBranch = baseBranch;
        this.githubOrg = githubOrg;
        this.description = description;
        this.draft = draft;
    }
}
