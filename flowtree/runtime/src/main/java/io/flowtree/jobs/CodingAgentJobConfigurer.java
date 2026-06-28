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

package io.flowtree.jobs;

import io.flowtree.JsonFieldExtractor;

import java.util.List;
import java.util.Map;

/**
 * Carries a {@link CodingAgentJobFactory}'s accumulated configuration onto a
 * freshly created {@link CodingAgentJob}.
 *
 * <p>Extracted from {@link CodingAgentJobFactory#nextJob()} so the factory stays
 * within the file-length limit: the factory remains responsible for prompt
 * iteration and job construction, while this collaborator owns the (long, purely
 * mechanical) field-by-field propagation. Every value is read through the
 * factory's public getters and written through the job's public setters, so the
 * mapping is behaviour-identical to the inlined version it replaced.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJobFactory#nextJob()
 */
final class CodingAgentJobConfigurer {

    /** Prevents instantiation; this class only exposes a static helper. */
    private CodingAgentJobConfigurer() {
    }

    /**
     * Applies {@code factory}'s configuration to {@code job}: tools, budget, git
     * coordinates, enforcement flags, post-completion command, dependent repos,
     * required labels, and the per-phase config bundle.
     *
     * @param factory the factory holding the configuration
     * @param job     the freshly constructed job to populate
     */
    static void applyConfiguration(CodingAgentJobFactory factory, CodingAgentJob job) {
        job.setAllowedTools(factory.getAllowedTools());
        job.setWorkingDirectory(factory.getWorkingDirectory());
        job.setMaxTurns(factory.getMaxTurns());
        job.setMaxBudgetUsd(factory.getMaxBudgetUsd());

        // Runner, model, effort, and provider are all carried by the
        // phaseConfigBundle and propagated together via setPhaseConfigBundle
        // below; no separate scalar runner/model/effort propagation is needed.

        String desc = factory.getDescription();
        if (desc != null) {
            job.setDescription(desc);
        }

        if (factory.getRepoUrl() != null) {
            job.setRepoUrl(factory.getRepoUrl());
        }
        if (factory.getDefaultWorkspacePath() != null) {
            job.setDefaultWorkspacePath(factory.getDefaultWorkspacePath());
        }

        if (factory.getTargetBranch() != null) {
            job.setTargetBranch(factory.getTargetBranch());
            job.setPushToOrigin(factory.isPushToOrigin());
        }
        if (factory.getBaseBranch() != null) {
            job.setBaseBranch(factory.getBaseBranch());
        }
        if (factory.getGitUserName() != null) {
            job.setGitUserName(factory.getGitUserName());
        }
        if (factory.getGitUserEmail() != null) {
            job.setGitUserEmail(factory.getGitUserEmail());
        }

        if (factory.getWorkstreamUrl() != null) {
            job.setWorkstreamUrl(factory.getWorkstreamUrl());
        }

        if (factory.getArManagerUrl() != null) {
            job.setArManagerUrl(factory.getArManagerUrl());
        }
        if (factory.getArManagerToken() != null) {
            job.setArManagerToken(factory.getArManagerToken());
        }
        job.setDispatchCapable(factory.isDispatchCapable());
        if (factory.getPushedToolsConfig() != null) {
            job.setPushedToolsConfig(factory.getPushedToolsConfig());
        } else {
            factory.warn("no pushedToolsConfig to propagate to " + job.getTaskId());
        }

        if (factory.getAgentEnvJson() != null) {
            job.setAgentEnv(JsonFieldExtractor.parseStringObject(factory.getAgentEnvJson()));
        }

        if (factory.getPlanningDocument() != null) {
            job.setPlanningDocument(factory.getPlanningDocument());
        }

        job.setProtectTestFiles(factory.isProtectTestFiles());
        job.setEnforceChanges(factory.isEnforceChanges());
        job.setDeduplicationMode(factory.getDeduplicationMode());
        job.setMaxDeduplicationPasses(factory.getMaxDeduplicationPasses());
        job.setEnforceMavenDependencies(factory.isEnforceMavenDependencies());
        job.setEnforceOrganizationalPlacement(factory.isEnforceOrganizationalPlacement());
        job.setUseTmux(factory.isUseTmux());
        job.setReviewEnabled(factory.isReviewEnabled());
        job.setMaxReviewPasses(factory.getMaxReviewPasses());
        job.setRetrospectiveEnabled(factory.isRetrospectiveEnabled());
        job.setFalsificationEnabled(factory.isFalsificationEnabled());
        job.setSensitiveFileProtectionEnabled(factory.isSensitiveFileProtectionEnabled());
        job.setSensitiveFileBypassSignature(factory.getSensitiveFileBypassSignature());
        applyPostCompletionConfig(factory, job);

        String pyReqs = factory.getPythonRequirements();
        if (pyReqs != null) {
            job.setPythonRequirements(pyReqs);
        }

        List<String> depRepos = factory.getDependentRepos();
        if (depRepos != null && !depRepos.isEmpty()) {
            job.setDependentRepos(depRepos);
        }

        for (Map.Entry<String, String> entry : factory.getRequiredLabels().entrySet()) {
            job.setRequiredLabel(entry.getKey(), entry.getValue());
        }

        // Propagate the full per-phase config bundle: it is the single source
        // of runner, model, effort, and provider (default and per-phase).
        // setPhaseConfigBundle re-derives the runner-resolution fields from it.
        if (!factory.getPhaseConfigBundle().isEmpty()) {
            job.setPhaseConfigBundle(factory.getPhaseConfigBundle());
        }
    }

    /**
     * Propagates the post-completion command and its options, but only when a
     * command is configured; the timeout and pass count are forwarded only when
     * they differ from the rule's defaults, matching the original inlined logic.
     *
     * @param factory the factory holding the configuration
     * @param job     the job to populate
     */
    private static void applyPostCompletionConfig(CodingAgentJobFactory factory, CodingAgentJob job) {
        String command = factory.getPostCompletionCommand();
        if (command == null || command.isEmpty()) {
            return;
        }
        job.setPostCompletionCommand(command);
        if (factory.getPostCompletionWorkingDir() != null) {
            job.setPostCompletionWorkingDir(factory.getPostCompletionWorkingDir());
        }
        if (factory.getPostCompletionTimeoutSeconds() != PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS) {
            job.setPostCompletionTimeoutSeconds(factory.getPostCompletionTimeoutSeconds());
        }
        if (factory.getMaxPostCompletionPasses() != CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES) {
            job.setMaxPostCompletionPasses(factory.getMaxPostCompletionPasses());
        }
    }
}
