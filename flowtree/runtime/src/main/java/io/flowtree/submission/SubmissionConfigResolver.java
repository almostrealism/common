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

package io.flowtree.submission;

import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.Map;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;
import io.flowtree.workstream.WorkspaceEntry;

/**
 * Orchestrator that runs {@link SubmissionRunnerResolver} and
 * {@link PhaseConfigResolver} together so every submission path applies the
 * same request &rarr; workstream &rarr; workspace &rarr; controller-default
 * resolution before a job leaves the controller.
 *
 * <p>Every path that builds a {@link CodingAgentJobFactory} from a
 * {@link Workstream} &mdash; the REST API endpoint, the Slack listener, any
 * future workstream-driven submission &mdash; should resolve through this
 * class. Constructing a factory by hand from {@link Workstream} getters
 * without invoking the resolver chain silently drops workspace-level Phase
 * runner settings; that bug is precisely what this class exists to prevent.</p>
 *
 * <p>Note on package placement: this orchestrator and the two resolvers it
 * composes live in {@code io.flowtree.slack} for historical reasons but are
 * not Slack-specific. The REST API endpoint, the controller, and any other
 * submission entrypoint are first-class callers. A package rename is tracked
 * as a follow-up.</p>
 */
public final class SubmissionConfigResolver {

    /** Underlying runner resolver; never {@code null}. */
    private final SubmissionRunnerResolver runnerResolver;

    /** Underlying Phase-config resolver; never {@code null}. */
    private final PhaseConfigResolver phaseConfigResolver;

    /** Private constructor; use {@link #resolve(PhaseConfigBundle, Workstream, WorkspaceEntry)}. */
    private SubmissionConfigResolver(SubmissionRunnerResolver runnerResolver,
                                     PhaseConfigResolver phaseConfigResolver) {
        this.runnerResolver = runnerResolver;
        this.phaseConfigResolver = phaseConfigResolver;
    }

    /**
     * Resolves runner and Phase configuration for a submission targeting the
     * given workstream. Both resolvers are always run; their errors (if any)
     * are merged into a single {@link #error()} so callers can short-circuit
     * with one branch.
     *
     * @param requestBundle the per-job {@link PhaseConfigBundle} parsed from
     *                      the submission body, or {@link PhaseConfigBundle#EMPTY}
     *                      when the submission carries no per-job overrides
     *                      (e.g. a Slack-triggered submission). {@code null}
     *                      is treated as {@link PhaseConfigBundle#EMPTY}.
     * @param workstream    the target workstream; must not be {@code null}
     * @param wsEntry       the owning workspace entry, or {@code null} when
     *                      the workstream has no {@code workspaceId} or it
     *                      does not resolve to a configured workspace
     * @return a fresh resolver
     */
    public static SubmissionConfigResolver resolve(PhaseConfigBundle requestBundle,
                                                   Workstream workstream,
                                                   WorkspaceEntry wsEntry) {
        PhaseConfigBundle req = requestBundle != null ? requestBundle : PhaseConfigBundle.EMPTY;

        // Legacy runner ladder. Request-side runners are no longer accepted on
        // the REST API (rejected upfront by PhaseConfigResolver.rejectLegacyRequestFields)
        // and the Slack path has no equivalent, so the request map is always empty.
        String workspaceDefault = wsEntry != null ? wsEntry.getDefaultRunner() : null;
        Map<String, String> workspaceRunners = wsEntry != null ? wsEntry.getRunners() : null;
        SubmissionRunnerResolver runner = SubmissionRunnerResolver.resolve(
                Map.of(),
                workstream.getDefaultRunner(), workstream.getRunners(),
                workspaceDefault, workspaceRunners);

        // Unified per-phase config ladder.
        PhaseConfigBundle workspaceBundle = wsEntry != null
                ? wsEntry.toPhaseConfigBundle()
                : PhaseConfigBundle.EMPTY;
        PhaseConfigResolver phaseConfig = PhaseConfigResolver.resolve(
                req, workstream.getPhaseConfigBundle(), workspaceBundle);

        return new SubmissionConfigResolver(runner, phaseConfig);
    }

    /**
     * Returns a 400-able validation error from either underlying resolver, or
     * {@code null} when both succeeded. The runner resolver's error takes
     * precedence so callers see the lower-level failure first.
     */
    public String error() {
        if (runnerResolver.error() != null) return runnerResolver.error();
        return phaseConfigResolver.error();
    }

    /**
     * Applies both resolvers to the factory. A no-op when {@link #error()} is
     * non-null, matching the semantics of each underlying resolver.
     *
     * @param factory the factory to mutate; must not be {@code null}
     */
    public void applyTo(CodingAgentJobFactory factory) {
        if (error() != null) return;
        runnerResolver.applyTo(factory);
        phaseConfigResolver.applyTo(factory);
    }

    /**
     * Returns the underlying Phase-config resolver so callers that need to
     * inspect the resolved bundle (e.g. to emit it in a response body) can
     * do so without re-running resolution.
     */
    public PhaseConfigResolver phaseConfigResolver() {
        return phaseConfigResolver;
    }
}
