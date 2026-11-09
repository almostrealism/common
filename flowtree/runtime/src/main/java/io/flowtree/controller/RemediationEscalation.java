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

package io.flowtree.controller;

import io.flowtree.github.PullRequestEscalation;

/**
 * Builds the body of the per-PR cap-reached ping {@link RemediationBudgetGuard}
 * posts when auto-dispatch stops: a short statement that the cap was reached,
 * followed by the PR's unresolved review threads and the TODO lines the branch
 * added, each with a link, so the person the loop hands off to knows exactly
 * what is still outstanding.
 *
 * <p>The PR facts are looked up through an injected {@link Lookup} — in
 * production {@code GitHubProxyHandler::escalationFor} — so the composition is
 * exercised without reaching GitHub. The lookup is best-effort: when it returns
 * {@link PullRequestEscalation#NONE} (no open PR, no token, a transport failure)
 * the ping still states the cap was reached and tells the person where to look,
 * rather than failing.</p>
 *
 * @author Michael Murray
 * @see RemediationBudgetGuard
 */
public final class RemediationEscalation {

    /** Resolves the PR facts to list for a workstream's (repoUrl, branch). */
    @FunctionalInterface
    public interface Lookup {
        /**
         * Returns the escalation facts for the open PR on {@code branch}.
         *
         * @param repoUrl the repository URL
         * @param branch  the workstream's branch (the PR head)
         * @return the facts, never {@code null}; {@link PullRequestEscalation#NONE}
         *         when none could be resolved
         */
        PullRequestEscalation lookup(String repoUrl, String branch);
    }

    /** The PR-facts lookup. */
    private final Lookup lookup;

    /**
     * Creates an escalation composer over {@code lookup}.
     *
     * @param lookup resolves the PR facts to list
     */
    public RemediationEscalation(Lookup lookup) {
        this.lookup = lookup;
    }

    /**
     * Composes the cap-reached ping for a workstream's branch.
     *
     * @param repoUrl the repository URL the workstream names; may be {@code null}
     * @param branch  the workstream's branch (the PR head); may be {@code null}
     * @param budget  the per-PR cap that was reached
     * @return the ping body (Markdown)
     */
    public String compose(String repoUrl, String branch, int budget) {
        StringBuilder message = new StringBuilder(RemediationBudgetGuard.capReachedMessage(budget));

        PullRequestEscalation pr = lookup.lookup(repoUrl, branch);
        if (pr == null) pr = PullRequestEscalation.NONE;
        pr.appendTo(message);
        return message.toString();
    }
}
