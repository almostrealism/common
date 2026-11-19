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

import io.flowtree.jobs.CodingAgentJobFactory;

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
    /**
     * Prompt-free description of the job, used as the PR title/body only when the
     * commit on the head branch yields none; see {@link #forJob}.
     */
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

    /**
     * Builds the auto-PR context for the jobs {@code factory} creates.
     *
     * <p>The description the pull request falls back to is the factory's explicit
     * description when one was submitted, and otherwise names the job
     * ({@code "Job <taskId>"}). It is never the task prompt: the title and body
     * fall back to it when the head commit's message cannot be read or carries no
     * explanation, and a prompt there would publish the agent's instructions as
     * the pull request's own description.
     * {@link CodingAgentJobFactory#getDisplaySummary()} is unsuitable for that
     * reason, since it returns a short prompt verbatim.</p>
     *
     * @param factory   the submitted factory supplying the repository, base branch,
     *                  description and task id
     * @param githubOrg GitHub organisation name, used to look up the API access token
     * @param draft     whether to open the pull request as a draft
     * @return the context to register for the factory's task id
     */
    static AutoPrContext forJob(CodingAgentJobFactory factory, String githubOrg, boolean draft) {
        String description = factory.getDescription();
        if (description == null || description.isBlank()) {
            description = "Job " + factory.getTaskId();
        }
        return new AutoPrContext(factory.getRepoUrl(), factory.getBaseBranch(), githubOrg,
                description, draft);
    }
}
