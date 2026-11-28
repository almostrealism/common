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

package io.flowtree.workstream;

import io.flowtree.git.RepositoryUrl;
import io.flowtree.github.CiTrigger;
import io.flowtree.github.GitHubProxyHandler;
import org.almostrealism.io.ConsoleFeatures;

import java.util.List;

/**
 * The set of git repositories a {@link Workstream} is bound to: the primary
 * repository it tracks and checks out, plus any dependent repositories cloned
 * alongside it as siblings.
 *
 * <p>This is a cohesive collaborator extracted from {@link Workstream} so the
 * repository-identity predicates live with the state they read. Every
 * comparison is by repository <em>identity</em> rather than string equality
 * (see {@link RepositoryUrl#isSameRepository(RepositoryUrl)}), so a caller
 * naming a repository in a different but equivalent form — SSH instead of
 * HTTPS, or without the {@code .git} suffix — still matches the binding that
 * tracks it. {@link Workstream} holds one of these and delegates its
 * {@code repoUrl}/{@code dependentRepos} accessors and the {@code matchesRepo}
 * / {@code dependsOnRepo} predicates to it, so the public API is unchanged.</p>
 *
 * @author Michael Murray
 * @see Workstream
 */
final class RepositoryBinding implements ConsoleFeatures {

    /** Git remote URL of the primary repository; when set the job clones it before starting work. */
    private String repoUrl;

    /** Additional repository URLs to clone alongside the primary repo. */
    private List<String> dependentRepos;

    /** CI started on the primary repository when a review loop settles; see {@link #getSettledCiTrigger()}. */
    private CiTrigger settledCiTrigger;

    /**
     * Returns the primary git repository URL for automatic checkout.
     *
     * @return the repository URL, or {@code null} when unset
     */
    String getRepoUrl() {
        return repoUrl;
    }

    /**
     * Sets the primary git repository URL for automatic checkout.
     *
     * @param repoUrl the git clone URL (e.g., "https://github.com/owner/repo.git")
     */
    void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    /**
     * Determines whether the primary repository is the one named by
     * {@code repoUrl}, comparing by repository identity.
     *
     * @param repoUrl the repository URL to test; may be {@code null}
     * @return {@code true} when both name the same repository; {@code false}
     *         when either side is absent
     */
    boolean matchesRepo(String repoUrl) {
        return new RepositoryUrl(this.repoUrl).isSameRepository(new RepositoryUrl(repoUrl));
    }

    /**
     * Returns the dependent repository URLs cloned as siblings of the primary
     * repo, or {@code null} when none are configured.
     *
     * @return the dependent repository URLs, or {@code null}
     */
    List<String> getDependentRepos() {
        return dependentRepos;
    }

    /**
     * Sets the dependent repository URLs.
     *
     * @param dependentRepos list of git clone URLs for dependent repos
     */
    void setDependentRepos(List<String> dependentRepos) {
        this.dependentRepos = dependentRepos;
    }

    /**
     * Determines whether any dependent repository is the one named by
     * {@code repoUrl}, comparing by repository identity.
     *
     * <p>This is how a workstream whose primary repo is a downstream
     * repository recognises a registration for one of the repositories it
     * builds on: the shared branch is covered by this workstream rather than
     * by a duplicate registered for the dependency.</p>
     *
     * @param repoUrl the repository URL to test; may be {@code null}
     * @return {@code true} when some dependent repository names the same
     *         repository as {@code repoUrl}; {@code false} when the list is
     *         empty or {@code repoUrl} is absent
     */
    boolean dependsOnRepo(String repoUrl) {
        if (dependentRepos == null || dependentRepos.isEmpty()) return false;
        if (repoUrl == null || repoUrl.isEmpty()) return false;
        for (String dependent : dependentRepos) {
            if (new RepositoryUrl(dependent).isSameRepository(new RepositoryUrl(repoUrl))) return true;
        }
        return false;
    }

    /**
     * Returns the CI started on each repository the workstream touches when its
     * review loop settles, or {@code null} for none.
     *
     * <p>A coding job that finishes successfully with nothing to commit is how
     * a review loop ends: each review that found something pushed a fix, which
     * started another validation and another review, until one found nothing.
     * That is when the expensive CI is worth running, so the controller starts
     * this trigger for the branch then. The trigger does nothing for a commit
     * that already has it, so the controller fires it on every such job
     * without tracking earlier ones. CI never depends on this: when the
     * controller is down the same CI can be started by hand.</p>
     *
     * <p>The trigger spec is repository-agnostic — {@code deployment:test}
     * starts the test run of whichever repository it targets — so the same
     * trigger applies to the primary repository and to each
     * {@link #getDependentRepos() dependent repository} that carries an open
     * pull request on the branch (see {@link #startSettledCi}). Each repository
     * gets its own invocation against its own head commit, so one review loop
     * that spans a downstream repository and a repository it builds on starts
     * the gating test run of both.</p>
     *
     * @return the trigger, or {@code null}
     */
    CiTrigger getSettledCiTrigger() {
        return settledCiTrigger;
    }

    /**
     * Sets the CI started on the primary repository when a review loop settles.
     *
     * @param settledCiTrigger the trigger, or {@code null} for none
     */
    void setSettledCiTrigger(CiTrigger settledCiTrigger) {
        this.settledCiTrigger = settledCiTrigger;
    }

    /**
     * Starts the {@link #getSettledCiTrigger() settled CI} for {@code branch} of
     * the primary repository and of every dependent repository that carries an
     * open pull request on the branch, when there is a trigger.
     *
     * <p>Does nothing for the base branch, which has its own CI on every push
     * and no review loop, and nothing when a repository is not on GitHub or no
     * token is configured for its organisation.</p>
     *
     * <p>A dependent repository is only triggered when it has an open pull
     * request on the branch: a review loop that touched only the primary
     * repository leaves the dependents without a companion pull request, and a
     * test run on a repository whose branch gates nothing would be wasted. The
     * primary repository is not gated this way — by the time its review loop
     * settles it has the pull request the loop ran on. Each dependent is
     * resolved against its own organisation token, since a downstream
     * repository and the repository it builds on commonly live in different
     * organisations.</p>
     *
     * @param github     the GitHub operations, holding the per-organisation tokens
     * @param githubOrg  the primary repository's organisation whose token to
     *                   use; {@code null} to derive it from the repository URL
     * @param baseBranch the workstream's base branch; {@code null} for {@code master}
     * @param branch     the branch whose review loop settled
     * @return the primary repository's outcome, or {@code null} when nothing was
     *         attempted for it; dependent-repository outcomes are logged
     */
    CiTrigger.Outcome startSettledCi(GitHubProxyHandler github, String githubOrg,
                                     String baseBranch, String branch) {
        if (settledCiTrigger == null || branch == null || branch.isEmpty()
                || branch.equals(baseBranch != null ? baseBranch : "master")) {
            return null;
        }
        CiTrigger.Outcome primary = startSettledCiForRepo(github, repoUrl, githubOrg, branch);
        if (dependentRepos != null) {
            for (String dependent : dependentRepos) {
                startSettledCiForDependent(github, dependent, branch);
            }
        }
        return primary;
    }

    /**
     * Starts the settled CI for {@code branch} of one repository, resolving its
     * organisation token.
     *
     * @param github    the GitHub operations, holding the per-organisation tokens
     * @param repoUrl   the repository to start CI for
     * @param githubOrg the organisation whose token to use; {@code null} to
     *                  derive it from {@code repoUrl}
     * @param branch    the branch whose review loop settled
     * @return the outcome, or {@code null} when the repository is not on GitHub
     *         or no token is configured for its organisation
     */
    private CiTrigger.Outcome startSettledCiForRepo(GitHubProxyHandler github, String repoUrl,
                                                    String githubOrg, String branch) {
        String ownerRepo = GitHubProxyHandler.extractOwnerRepo(repoUrl);
        if (ownerRepo == null) {
            return null;
        }
        String org = githubOrg != null && !githubOrg.isEmpty()
                ? githubOrg : GitHubProxyHandler.extractOrgFromRepoUrl(repoUrl);
        String token = github.resolveGithubToken(org);
        if (token == null) {
            warn("Settled CI " + settledCiTrigger.spec() + " for " + ownerRepo + " " + branch
                    + " not started: no GitHub token is configured for " + org);
            return null;
        }
        return settledCiTrigger.start(github, ownerRepo, branch, token);
    }

    /**
     * Starts the settled CI for a dependent repository, but only when it carries
     * an open pull request on {@code branch}. A dependent without one has no
     * companion pull request this branch's work touched, so there is nothing a
     * test run would gate.
     *
     * @param github            the GitHub operations
     * @param dependentRepoUrl  the dependent repository URL
     * @param branch            the branch whose review loop settled
     */
    private void startSettledCiForDependent(GitHubProxyHandler github,
                                            String dependentRepoUrl, String branch) {
        String ownerRepo = GitHubProxyHandler.extractOwnerRepo(dependentRepoUrl);
        if (ownerRepo == null) {
            return;
        }
        if (github.findOpenPullRequest(ownerRepo, branch) == null) {
            return;
        }
        startSettledCiForRepo(github, dependentRepoUrl, null, branch);
    }
}
