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

/**
 * Submission-time configuration resolution.
 *
 * <p>The resolvers in this package walk the unified
 * request &rarr; workstream &rarr; workspace &rarr; controller-default ladder
 * to determine the final per-phase agent configuration for a submitted job,
 * before the job leaves the controller:</p>
 * <ul>
 *   <li>{@link io.flowtree.submission.SubmissionRunnerResolver} &mdash;
 *       runner-only ladder (legacy).</li>
 *   <li>{@link io.flowtree.submission.PhaseConfigResolver} &mdash; unified
 *       per-phase ladder over {@code (runner, model, effort, provider)}.</li>
 *   <li>{@link io.flowtree.submission.SubmissionConfigResolver} &mdash;
 *       orchestrator combining the two above so every submission entrypoint
 *       (HTTP API, Slack listener, future workstream-driven submission) sees
 *       the same resolution semantics.</li>
 * </ul>
 *
 * <p>The orchestrator is what every submission path is expected to call.
 * Constructing a {@link io.flowtree.jobs.CodingAgentJobFactory} from
 * {@link io.flowtree.workstream.Workstream} getters without going through
 * the orchestrator silently drops workspace-level Phase runner settings.</p>
 */
package io.flowtree.submission;
