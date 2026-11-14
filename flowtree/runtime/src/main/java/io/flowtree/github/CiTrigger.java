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

package io.flowtree.github;

/**
 * A way of starting GitHub Actions CI for the head commit of a branch, at most
 * once per commit.
 *
 * <p>Written as a spec string, which is how a workstream stores it:</p>
 * <ul>
 *   <li>{@code deployment:<environment>} creates a deployment of the branch to
 *       {@code environment}, which starts every workflow triggered on the
 *       {@code deployment} event. Checks from such a run count toward a pull
 *       request's required status checks.</li>
 *   <li>{@code workflow:<file>} dispatches the workflow {@code file} (e.g.
 *       {@code tests.yaml}) on the branch. Checks from a dispatched run do
 *       <em>not</em> count toward a pull request's required status checks, so
 *       this suits CI that gates nothing.</li>
 * </ul>
 *
 * <p>{@link #start} first asks GitHub whether the branch's head commit already
 * has a deployment to that environment, or a run of that workflow, and does
 * nothing when it does. The check makes repeated triggers for one commit
 * harmless, which is what lets the caller fire on every qualifying event
 * without tracking what it fired before.</p>
 *
 * @param kind   how CI is started
 * @param target the environment name or workflow file name
 */
public record CiTrigger(Kind kind, String target) {

    /** How a {@link CiTrigger} starts CI. */
    public enum Kind {
        /** Create a deployment to the target environment. */
        DEPLOYMENT("deployment"),
        /** Dispatch the target workflow file. */
        WORKFLOW("workflow");

        /** The spec-string prefix naming this kind. */
        private final String prefix;

        /**
         * Creates a kind written with {@code prefix} in a spec string.
         *
         * @param prefix the spec-string prefix
         */
        Kind(String prefix) {
            this.prefix = prefix;
        }
    }

    /** The outcome of {@link #start}. */
    public enum Outcome {
        /** CI was started for the head commit. */
        STARTED,
        /** The head commit already had CI started this way; nothing was done. */
        ALREADY_STARTED,
        /** The branch head could not be read or GitHub refused the request. */
        FAILED
    }

    /**
     * Validates both parts.
     *
     * @throws IllegalArgumentException when either part is missing
     */
    public CiTrigger {
        if (kind == null || target == null || target.isBlank()) {
            throw new IllegalArgumentException("A CI trigger needs a kind and a target");
        }
    }

    /**
     * Parses a spec string.
     *
     * @param spec {@code deployment:<environment>} or {@code workflow:<file>};
     *             {@code null} or blank for none
     * @return the trigger, or {@code null} when {@code spec} is null or blank
     * @throws IllegalArgumentException when {@code spec} names no known kind or
     *                                  no target
     */
    public static CiTrigger parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String trimmed = spec.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            String prefix = trimmed.substring(0, colon);
            String target = trimmed.substring(colon + 1).trim();
            for (Kind kind : Kind.values()) {
                if (kind.prefix.equals(prefix) && !target.isEmpty()) {
                    return new CiTrigger(kind, target);
                }
            }
        }
        throw new IllegalArgumentException("Unknown CI trigger '" + spec
                + "'. Expected deployment:<environment> or workflow:<file>.");
    }

    /**
     * Returns the spec string this trigger parses from.
     *
     * @return {@code <kind>:<target>}
     */
    public String spec() {
        return kind.prefix + ":" + target;
    }

    /**
     * Starts CI for the head commit of {@code branch}, unless that commit
     * already has it.
     *
     * <p>The deployment or dispatch names the branch, not the SHA that was
     * checked: a deployment-triggered run sees the branch as its ref, which is
     * what its concurrency group and branch-scoped steps key on. A push that
     * lands between the check and the request is a newer head, which wants CI
     * anyway.</p>
     *
     * @param github    the GitHub operations
     * @param ownerRepo the repository's {@code owner/repo}
     * @param branch    the branch whose head commit to start CI for
     * @param token     a token that can create deployments or dispatch
     *                  workflows; a deployment created with a workflow's own
     *                  token starts no workflow
     * @return what happened
     */
    public Outcome start(GitHubProxyHandler github, String ownerRepo, String branch, String token) {
        String sha = github.fetchHeadSha(ownerRepo, branch, token);
        if (sha == null) {
            return Outcome.FAILED;
        }
        boolean started = kind == Kind.DEPLOYMENT
                ? github.hasDeployment(ownerRepo, sha, target, token)
                : github.hasWorkflowRun(ownerRepo, target, sha, token);
        if (started) {
            return Outcome.ALREADY_STARTED;
        }
        boolean ok = kind == Kind.DEPLOYMENT
                ? github.createDeployment(ownerRepo, branch, target,
                        "Review loop settled; starting CI", token)
                : github.dispatchWorkflow(ownerRepo, target, branch, token);
        return ok ? Outcome.STARTED : Outcome.FAILED;
    }

    @Override
    public String toString() {
        return spec();
    }
}
