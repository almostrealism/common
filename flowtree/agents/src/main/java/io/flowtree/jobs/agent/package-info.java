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
 * Pluggable agent-runner abstraction used by
 * {@link io.flowtree.jobs.CodingAgentJob} to dispatch each work session.
 *
 * <p>{@link io.flowtree.jobs.agent.AgentRunner} is the single contract every
 * runner implements; {@link io.flowtree.jobs.agent.AgentRunRequest} and
 * {@link io.flowtree.jobs.agent.AgentRunResult} carry the per-session input
 * and output across the boundary.</p>
 *
 * <p>The runner resolved for a session comes from a four-field per-phase
 * configuration: {@link io.flowtree.jobs.agent.PhaseConfig} carries
 * {@code (runner, model, effort, provider)}, and the per-container
 * {@link io.flowtree.jobs.agent.PhaseConfigBundle} holds a
 * {@code defaultPhaseConfig} plus a
 * {@code Map<Phase, PhaseConfig>} of per-phase overrides. Each field is
 * independently nullable and resolved separately against the same
 * per-job / per-workstream / per-workspace / controller-default ladder.</p>
 *
 * <p>Built-in runners today:</p>
 * <ul>
 *   <li>{@link io.flowtree.jobs.agent.ClaudeCodeRunner} — drives the Claude
 *       Code CLI.</li>
 *   <li>{@link io.flowtree.jobs.agent.OpencodeRunner} — drives the opencode
 *       CLI, typically pointed at a local OpenAI-compatible inference server.</li>
 * </ul>
 *
 * <p>{@link io.flowtree.jobs.agent.OpencodeTranscriptWriter} writes a structured
 * JSONL transcript for every opencode session, capturing the full NDJSON event
 * stream alongside session-context metadata (job ID, workstream ID, phase, model,
 * provider) and outcome metrics. Transcripts are written to a configurable
 * directory (see {@link io.flowtree.jobs.agent.OpencodeTranscriptWriter#ENV_TRANSCRIPT_DIR})
 * and survive job completion for postmortem analysis.</p>
 */
package io.flowtree.jobs.agent;
