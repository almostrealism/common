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
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.PhaseConfigBundle;

/**
 * Wire-format codec for {@link CodingAgentJob}.
 *
 * <p>Owns the serialization layout for the job's own fields ({@code prompt},
 * {@code tools}, {@code maxTurns}, enforcement-rule flags, etc.) so
 * {@link CodingAgentJob} itself is not weighed down by encode/decode
 * boilerplate. State held by the job's collaborators is encoded by the
 * collaborator that owns it: {@link RestartGovernor} carries the job-wide
 * ceilings, {@link PostCompletionConfig} the post-completion command, and
 * {@link PhaseRunnerConfig} the runner identity and
 * {@link PhaseConfigBundle}. Inherited fields from {@link GitManagedJob} are
 * encoded/decoded by the parent.</p>
 *
 * <p>The public protocol entry points remain {@link CodingAgentJob#encode()}
 * and {@link CodingAgentJob#set(String, String)}, which delegate to
 * {@link #appendEncoded(StringBuilder, CodingAgentJob)} and
 * {@link #applySetting(CodingAgentJob, String, String)} respectively.</p>
 */
final class CodingAgentJobCodec {

    /** Prevents instantiation; this class only exposes static helpers. */
    private CodingAgentJobCodec() {
    }

    /**
     * Appends {@link CodingAgentJob}-specific key/value pairs to {@code sb}
     * in the {@code ::key:=value} wire format used by {@link CodingAgentJob#encode()}.
     *
     * @param sb  buffer to append into; usually already contains the
     *            inherited fields produced by {@link GitManagedJob#encode()}
     * @param job the job whose state is being encoded
     */
    static void appendEncoded(StringBuilder sb, CodingAgentJob job) {
        sb.append("::prompt:=").append(GitManagedJob.base64Encode(job.getPrompt()));
        sb.append("::tools:=").append(GitManagedJob.base64Encode(job.getAllowedTools()));
        sb.append("::maxTurns:=").append(job.getMaxTurns());
        sb.append("::maxBudget:=").append(job.getMaxBudgetUsd());
        if (job.getArManagerUrl() != null) {
            sb.append("::arManagerUrl:=").append(GitManagedJob.base64Encode(job.getArManagerUrl()));
        }
        if (job.getArManagerToken() != null) {
            sb.append("::arManagerToken:=").append(GitManagedJob.base64Encode(job.getArManagerToken()));
        }
        if (job.getPushedToolsConfig() != null) {
            sb.append("::pushedTools:=").append(GitManagedJob.base64Encode(job.getPushedToolsConfig()));
        }
        if (job.getAgentEnv() != null && !job.getAgentEnv().isEmpty()) {
            sb.append("::agentEnv:=").append(
                    GitManagedJob.base64Encode(JsonFieldExtractor.toJsonObject(job.getAgentEnv())));
        }
        if (job.getPlanningDocument() != null) {
            sb.append("::planDoc:=").append(GitManagedJob.base64Encode(job.getPlanningDocument()));
        }
        sb.append("::protectTests:=").append(job.isProtectTestFiles());
        sb.append("::enforceChanges:=").append(job.isEnforceChanges());
        // dispatchCapable defaults to false; emit only when set so the wire
        // stays minimal. Without this key the flag is dropped on any
        // encode/decode round-trip (e.g. dispatch to a remote agent node),
        // which silently denies an orchestrator the workstream_register /
        // workstream_update_config tools it was granted.
        if (job.isDispatchCapable()) {
            sb.append("::dispatchCapable:=true");
        }
        // Emitted only when granted, for the same reason as dispatchCapable:
        // dropping it on a dispatch denies the job the writes it exists to make.
        if (job.isBypassAgentPermissionPrompts()) {
            sb.append("::bypassAgentPermissionPrompts:=true");
        }
        if (job.getDeduplicationMode() != null) {
            sb.append("::dedupMode:=").append(job.getDeduplicationMode());
        }
        if (job.getMaxDeduplicationPasses() != CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES) {
            sb.append("::maxDedupPasses:=").append(job.getMaxDeduplicationPasses());
        }
        if (job.isEnforceMavenDependencies()) {
            sb.append("::enforceMavenDeps:=true");
        }
        if (job.isEnforceOrganizationalPlacement()) {
            sb.append("::enforceOrgPlacement:=true");
        }
        if (job.isObjectOrientedReviewEnabled()) {
            sb.append("::objectOrientedReviewEnabled:=true");
        }
        if (job.isUseTmux()) {
            sb.append("::useTmux:=true");
        }
        if (!job.isReviewEnabled()) {
            sb.append("::reviewEnabled:=false");
        }
        if (job.isRetrospectiveEnabled()) {
            sb.append("::retrospectiveEnabled:=true");
        }
        if (job.isFalsificationEnabled()) {
            sb.append("::falsificationEnabled:=true");
        }
        if (job.isCollaborative()) {
            sb.append("::collaborative:=true");
        }
        // sensitiveFileProtectionEnabled defaults to TRUE; emit only when false.
        if (!job.isSensitiveFileProtectionEnabled()) {
            sb.append("::sensitiveFileProtectionEnabled:=false");
        }
        String bypassSig = job.getSensitiveFileBypassSignature();
        if (bypassSig != null && !bypassSig.isEmpty()) {
            sb.append("::sensitiveFileBypassSignature:=")
                    .append(GitManagedJob.base64Encode(bypassSig));
        }
        if (job.getMaxReviewPasses() != CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES) {
            sb.append("::maxReviewPasses:=").append(job.getMaxReviewPasses());
        }
    }

    /**
     * Applies a single wire-format key/value pair to {@code job}.
     *
     * @param job   the job to mutate
     * @param key   the wire-format key (without the leading {@code ::})
     * @param value the raw value (still Base64 for string fields)
     * @return {@code true} if {@code key} was recognised and consumed,
     *         {@code false} if the caller should delegate to
     *         {@link GitManagedJob#set(String, String)}
     */
    static boolean applySetting(CodingAgentJob job, String key, String value) {
        switch (key) {
            case "prompt":
                job.setPrompt(GitManagedJob.base64Decode(value));
                return true;
            case "tools":
                job.setAllowedTools(GitManagedJob.base64Decode(value));
                return true;
            case "maxTurns":
                job.setMaxTurns(Integer.parseInt(value));
                return true;
            case "maxBudget":
                job.setMaxBudgetUsd(Double.parseDouble(value));
                return true;
            case "arManagerUrl":
                job.setArManagerUrl(GitManagedJob.base64Decode(value));
                return true;
            case "arManagerToken":
                job.setArManagerToken(GitManagedJob.base64Decode(value));
                return true;
            case "pushedTools":
                job.setPushedToolsConfig(GitManagedJob.base64Decode(value));
                return true;
            case "agentEnv":
                job.setAgentEnv(JsonFieldExtractor.parseStringObject(GitManagedJob.base64Decode(value)));
                return true;
            case "planDoc":
                job.setPlanningDocument(GitManagedJob.base64Decode(value));
                return true;
            case "protectTests":
                job.setProtectTestFiles(Boolean.parseBoolean(value));
                return true;
            case "enforceChanges":
                job.setEnforceChanges(Boolean.parseBoolean(value));
                return true;
            case "dispatchCapable":
                job.setDispatchCapable(Boolean.parseBoolean(value));
                return true;
            case "bypassAgentPermissionPrompts":
                job.setBypassAgentPermissionPrompts(Boolean.parseBoolean(value));
                return true;
            case "dedupMode":
                job.setDeduplicationMode(value);
                return true;
            case "maxDedupPasses":
                job.setMaxDeduplicationPasses(parsePositiveOrDefault(value, CodingAgentJob.DEFAULT_MAX_DEDUP_PASSES));
                return true;
            case "enforceMavenDeps":
                job.setEnforceMavenDependencies(Boolean.parseBoolean(value));
                return true;
            case "enforceOrgPlacement":
                job.setEnforceOrganizationalPlacement(Boolean.parseBoolean(value));
                return true;
            case "objectOrientedReviewEnabled":
                job.setObjectOrientedReviewEnabled(Boolean.parseBoolean(value));
                return true;
            case "useTmux":
                job.setUseTmux(Boolean.parseBoolean(value));
                return true;
            case "reviewEnabled":
                job.setReviewEnabled(Boolean.parseBoolean(value));
                return true;
            case "retrospectiveEnabled":
                job.setRetrospectiveEnabled(Boolean.parseBoolean(value));
                return true;
            case "falsificationEnabled":
                job.setFalsificationEnabled(Boolean.parseBoolean(value));
                return true;
            case "collaborative":
                job.setCollaborative(Boolean.parseBoolean(value));
                return true;
            case "sensitiveFileProtectionEnabled":
                job.setSensitiveFileProtectionEnabled(Boolean.parseBoolean(value));
                return true;
            case "sensitiveFileBypassSignature":
                job.setSensitiveFileBypassSignature(GitManagedJob.base64Decode(value));
                return true;
            case "maxReviewPasses":
                job.setMaxReviewPasses(parsePositiveOrDefault(value, CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES));
                return true;
            case "runner":
                // Legacy single-runner key. Honored when no defaultRunner/runners
                // key supersedes it; consumed silently if defaultRunner is also
                // present (the wire format guarantees that key precedence).
                if (AgentRunnerRegistry.CLAUDE.equals(job.getDefaultRunner())) {
                    job.setRunnerName(value);
                }
                return true;
            case "defaultRunner":
                job.setRunnerName(value);
                return true;
            case "runners":
                job.applyRunnerMap(value);
                return true;
            case "phaseConfigBundle":
                // Bundle carries per-phase model/effort/provider that legacy
                // wire keys cannot represent. setPhaseConfigBundle re-syncs
                // the legacy fields, so it is safe regardless of arrival order.
                job.setPhaseConfigBundle(PhaseConfigBundle.fromWire(value));
                return true;
            default:
                return false;
        }
    }

    /**
     * Parses {@code value} as a positive {@code int}; returns {@code fallback}
     * when the value is {@code null}, empty, unparseable, or not positive. Used
     * by capped-pass settings whose wire representations must round-trip through
     * legacy encodings that may contain absent or invalid integers.
     *
     * @param value    the wire-format value (may be {@code null} or empty)
     * @param fallback the default returned on parse failure or non-positive input
     * @return the parsed positive int, or {@code fallback}
     */
    static int parsePositiveOrDefault(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
