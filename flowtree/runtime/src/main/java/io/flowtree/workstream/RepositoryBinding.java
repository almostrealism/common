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

import io.flowtree.jobs.GitOperations;

import java.util.List;

/**
 * The set of git repositories a {@link Workstream} is bound to: the primary
 * repository it tracks and checks out, plus any dependent repositories cloned
 * alongside it as siblings.
 *
 * <p>This is a cohesive collaborator extracted from {@link Workstream} so the
 * repository-identity predicates live with the state they read. Every
 * comparison is by repository <em>identity</em> rather than string equality
 * (see {@link GitOperations#isSameRepository(String, String)}), so a caller
 * naming a repository in a different but equivalent form — SSH instead of
 * HTTPS, or without the {@code .git} suffix — still matches the binding that
 * tracks it. {@link Workstream} holds one of these and delegates its
 * {@code repoUrl}/{@code dependentRepos} accessors and the {@code matchesRepo}
 * / {@code dependsOnRepo} predicates to it, so the public API is unchanged.</p>
 *
 * @author Michael Murray
 * @see Workstream
 */
final class RepositoryBinding {

    /** Git remote URL of the primary repository; when set the job clones it before starting work. */
    private String repoUrl;

    /** Additional repository URLs to clone alongside the primary repo. */
    private List<String> dependentRepos;

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
        return GitOperations.isSameRepository(this.repoUrl, repoUrl);
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
            if (GitOperations.isSameRepository(dependent, repoUrl)) return true;
        }
        return false;
    }
}
