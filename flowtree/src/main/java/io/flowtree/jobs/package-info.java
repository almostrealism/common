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
 * Job implementations and supporting infrastructure for the FlowTree
 * workflow orchestration system.
 *
 * <h2>Overview</h2>
 * <p>This package provides the concrete {@link io.flowtree.job.Job} implementations
 * and helper classes used by FlowTree workers and controllers to execute
 * units of work. Jobs range from simple shell script execution to
 * full autonomous coding agent runs with git management, MCP tool
 * configuration, and workstream integration.</p>
 *
 * <h2>Key classes</h2>
 * <dl>
 *   <dt>{@link io.flowtree.jobs.ExternalProcessJob}</dt>
 *   <dd>Executes a list of shell commands as an external subprocess.
 *       The {@code Factory} inner class supports batching multiple command
 *       sets into a sequence of jobs.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.TemporalJob}</dt>
 *   <dd>Drives a {@link org.almostrealism.time.Temporal} simulation by
 *       ticking it on the worker thread, either indefinitely or for a
 *       fixed number of iterations.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.GitJobConfig}</dt>
 *   <dd>Immutable configuration for git-managed jobs: target branch, base
 *       branch, exclusion patterns, push policy, and identity settings.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.GitOperations}</dt>
 *   <dd>Encapsulates git and general subprocess execution, including PATH
 *       augmentation for headless environments and non-interactive SSH.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.FileStager}</dt>
 *   <dd>Stateless utility for evaluating which changed files should be
 *       staged, applying pattern exclusion, test file protection, size,
 *       and binary-detection guardrails.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.FileStagingConfig}</dt>
 *   <dd>Immutable configuration for file staging guardrails.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.StagingResult}</dt>
 *   <dd>Immutable result of a staging evaluation: staged files and skipped
 *       files with reasons.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.ClaudeCodeJob}</dt>
 *   <dd>Runs a Claude Code agent session inside a git-managed workspace,
 *       with optional enforcement rules (enforce-changes, deduplication,
 *       Maven dependency protection) that trigger correction sessions when
 *       violated. Extend or configure via {@link io.flowtree.jobs.ClaudeCodeJobFactory}.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.EnforcementRule}</dt>
 *   <dd>Interface for post-session checks evaluated by {@link io.flowtree.jobs.ClaudeCodeJob}.
 *       Implement this to add custom rules; built-in rules (enforce-changes,
 *       deduplication, Maven dependency protection) are activated by flags on
 *       the job or factory.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.McpConfigBuilder}</dt>
 *   <dd>Builds the MCP configuration JSON and allowed-tools string for
 *       Claude Code agent invocations.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.McpToolDiscovery}</dt>
 *   <dd>Scans Python MCP server source files to discover tool names and
 *       parameter signatures using three common registration patterns.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.ManagedToolsDownloader}</dt>
 *   <dd>Downloads pushed MCP tool server files from the controller and
 *       verifies their presence in the working directory.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.InstructionPromptBuilder}</dt>
 *   <dd>Builds the full instruction prompt that wraps a user request with
 *       operational context for autonomous coding agent execution.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.WorkspaceResolver}</dt>
 *   <dd>Resolves workspace paths and workstream URLs for git-managed jobs.</dd>
 *
 *   <dt>{@link io.flowtree.jobs.PullRequestDetector}</dt>
 *   <dd>Detects open GitHub pull requests for a given branch via the
 *       controller proxy endpoint.</dd>
 * </dl>
 *
 * @author Michael Murray
 */
package io.flowtree.jobs;
