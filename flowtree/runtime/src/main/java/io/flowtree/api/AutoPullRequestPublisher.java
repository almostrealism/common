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

import io.flowtree.github.GitHubProxyHandler;
import io.flowtree.github.PullRequest;
import io.flowtree.jobs.DependentRepoPush;
import io.flowtree.jobs.JobCompletionEvent;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens the pull requests a successfully-completed job asked for: the primary
 * one on the job's own repository, and a <em>companion</em> pull request for
 * each commit the job pushed to a dependent repository.
 *
 * <p>This is the pending auto-PR registry and completion behaviour the
 * {@code FlowTreeApiEndpoint} used to hold inline, extracted so the endpoint's
 * status-event handler only has to {@link #register} a job at submission time
 * and {@link #publish} it on completion.</p>
 *
 * <h2>Why a companion pull request</h2>
 * <p>A job can change a dependent repository, commit, and push there, but
 * nothing opened a pull request for those commits. A dependent-repo change with
 * no pull request is invisible to the reviewer and cannot merge through the
 * normal flow; once the primary change merges, the dependent repo's default
 * branch no longer carries the change and breaks. The companion pull request
 * makes the dependent-repo work visible and mergeable, cross-linked with the
 * primary.</p>
 *
 * <h2>The nesting the companion step must avoid</h2>
 * <p>The primary pull request is opened only when none already exists; a job
 * that <em>detected</em> an open primary PR skips that step. The companion step
 * must NOT sit inside that guard, or a job whose primary PR already exists — the
 * very case the companion feature exists for — would open no companion. So
 * {@link #publish} runs the primary step under its own conditions and then the
 * companion step under its own.</p>
 *
 * @author Michael Murray
 */
final class AutoPullRequestPublisher implements ConsoleFeatures {

    /** The GitHub operations used to open, find, and comment on pull requests. */
    private final GitHubProxyHandler github;

    /**
     * Jobs that requested a pull request on success, keyed by task id. Populated
     * by {@link #register} at submission time and consumed by {@link #publish} /
     * {@link #discard} on completion. Concurrent because submission and status
     * events are served on different request threads.
     */
    private final Map<String, AutoPrContext> autoCreatePrJobs = new ConcurrentHashMap<>();

    /**
     * Constructs a publisher over the given GitHub operations.
     *
     * @param github the handler that performs the GitHub REST calls
     */
    AutoPullRequestPublisher(GitHubProxyHandler github) {
        this.github = github;
    }

    /**
     * Records that the job {@code taskId} asked for a pull request on success.
     *
     * @param taskId  the job's task id
     * @param context the auto-PR context captured at submission time
     */
    void register(String taskId, AutoPrContext context) {
        if (taskId != null && context != null) {
            autoCreatePrJobs.put(taskId, context);
        }
    }

    /**
     * Drops any pending auto-PR context for {@code taskId} without opening a
     * pull request. Called for non-success completions.
     *
     * @param taskId the job's task id
     */
    void discard(String taskId) {
        if (taskId != null) {
            autoCreatePrJobs.remove(taskId);
        }
    }

    /**
     * Opens the pull requests a successful completion asked for and records
     * their URLs on {@code event}.
     *
     * <p>Opens the primary pull request when the job requested one and none was
     * detected, then — independently — opens or reuses a companion pull request
     * for every dependent-repo push. When the job requested no pull request but
     * pushed to a dependent repo, nothing is opened and the unreviewed commits
     * are logged.</p>
     *
     * @param event the successful completion event, mutated in place with the
     *              primary and companion pull-request URLs
     */
    void publish(JobCompletionEvent event) {
        AutoPrContext prCtx = event.getJobId() != null
                ? autoCreatePrJobs.remove(event.getJobId()) : null;

        String primaryOwnerRepo = null;
        String primaryToken = null;
        if (prCtx != null) {
            String org = (prCtx.githubOrg != null && !prCtx.githubOrg.isEmpty())
                    ? prCtx.githubOrg : GitHubProxyHandler.extractOrgFromRepoUrl(prCtx.repoUrl);
            primaryToken = github.resolveGithubToken(org);
            primaryOwnerRepo = GitHubProxyHandler.extractOwnerRepo(prCtx.repoUrl);
        }

        // Primary pull request: only when requested and none already exists.
        if (prCtx != null && event.getTargetBranch() != null && event.getPullRequestUrl() == null) {
            openPrimaryPullRequest(event, prCtx, primaryOwnerRepo, primaryToken);
        }

        // Companion pull requests: run under their own conditions, not nested in
        // the primary guard above.
        List<DependentRepoPush> pushes = event.getDependentRepoPushes();
        if (pushes.isEmpty()) {
            return;
        }
        if (prCtx == null) {
            warn("Dependent-repo commits are unreviewed -- no pull request was requested for this"
                    + " job, so no companion pull request was opened: " + DependentRepoPush.describe(pushes));
            return;
        }

        String primaryUrl = event.getPullRequestUrl();
        List<String> companionUrls = new ArrayList<>();
        for (DependentRepoPush push : pushes) {
            String companion = publishCompanion(push, prCtx, primaryUrl, primaryOwnerRepo, primaryToken);
            if (companion != null) {
                companionUrls.add(companion);
            }
        }
        event.withCompanionPullRequestUrls(companionUrls);
    }

    /**
     * Opens the primary pull request on the job's own repository and records its
     * URL on {@code event}.
     *
     * @param event       the completion event to record the URL on
     * @param prCtx       the auto-PR context captured at submission time
     * @param ownerRepo   the primary repo's {@code owner/repo}, or {@code null}
     * @param token       the primary org's token, or {@code null}
     */
    private void openPrimaryPullRequest(JobCompletionEvent event, AutoPrContext prCtx,
            String ownerRepo, String token) {
        if (token == null) {
            log("Cannot auto-create PR: no GitHub token available for the primary repo " + prCtx.repoUrl);
            return;
        }
        if (ownerRepo == null) {
            log("Cannot auto-create PR: unable to extract owner/repo from " + prCtx.repoUrl);
            return;
        }
        String base = prCtx.baseBranch != null ? prCtx.baseBranch : "master";
        // The branch head stands in when the event carried no primary commit
        // hash (e.g. a job whose only commit went to a dependent repo).
        String commitRef = event.getCommitHash() != null
                ? event.getCommitHash() : event.getTargetBranch();
        String prUrl = github.createPullRequestFromCommit(ownerRepo, event.getTargetBranch(),
                base, commitRef, prCtx.description, prCtx.draft, token);
        if (prUrl != null) {
            event.withPullRequestUrl(prUrl);
        }
    }

    /**
     * Opens or reuses the companion pull request for a single dependent-repo
     * push, and — when newly opened and a primary PR exists — comments the
     * cross-link on the primary.
     *
     * @param push              the dependent-repo push
     * @param prCtx             the auto-PR context (for draft flag and fallback)
     * @param primaryUrl        the primary pull request URL, or {@code null}
     * @param primaryOwnerRepo  the primary repo's {@code owner/repo}, or {@code null}
     * @param primaryToken      the primary org's token, or {@code null}
     * @return the companion pull request URL, or {@code null} when none was opened
     */
    private String publishCompanion(DependentRepoPush push, AutoPrContext prCtx, String primaryUrl,
            String primaryOwnerRepo, String primaryToken) {
        String org = GitHubProxyHandler.extractOrgFromRepoUrl(push.repoUrl());
        String token = github.resolveGithubToken(org);
        if (token == null) {
            warn("No GitHub token for dependent-repo org '" + org
                    + "'; cannot open a companion PR for " + push.repoUrl());
            return null;
        }
        String ownerRepo = GitHubProxyHandler.extractOwnerRepo(push.repoUrl());
        if (ownerRepo == null) {
            warn("Cannot open a companion PR: unable to extract owner/repo from " + push.repoUrl());
            return null;
        }

        String defaultBranch = github.fetchDefaultBranch(ownerRepo, token);
        if (defaultBranch == null) {
            defaultBranch = "master";
        }
        if (defaultBranch.equals(push.branch())
                || !github.isAhead(ownerRepo, defaultBranch, push.branch(), token)) {
            log("Dependent repo " + ownerRepo + " branch " + push.branch()
                    + " is not ahead of " + defaultBranch + "; no companion PR");
            return null;
        }

        PullRequest existing = github.findOpenPullRequest(ownerRepo, push.branch());
        if (existing != null) {
            log("Reusing open companion PR for " + ownerRepo + " branch " + push.branch());
            return existing.htmlUrl();
        }

        String suffix = primaryUrl != null
                ? "Companion to the primary pull request: " + primaryUrl : "";
        String companionUrl = github.createPullRequestFromCommit(ownerRepo, push.branch(),
                defaultBranch, push.commitHash(), prCtx.description, prCtx.draft, token, suffix);
        if (companionUrl == null) {
            return null;
        }
        if (primaryUrl != null && primaryOwnerRepo != null && primaryToken != null) {
            int number = PullRequest.numberFromUrl(primaryUrl);
            if (number > 0) {
                github.commentOnPullRequest(primaryOwnerRepo, number,
                        "Companion pull request opened in " + ownerRepo + ": " + companionUrl
                                + "\n\nBoth pull requests must merge together.", primaryToken);
            }
        }
        return companionUrl;
    }
}
