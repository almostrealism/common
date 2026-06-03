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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.Map;

/**
 * Wire-format codec for {@link CodingAgentJob}.
 *
 * <p>Owns the serialization layout for job-specific fields ({@code prompt},
 * {@code tools}, {@code maxTurns}, runner overrides, enforcement-rule flags,
 * etc.) so {@link CodingAgentJob} itself is not weighed down by encode/decode
 * boilerplate. Inherited fields from {@link GitManagedJob} are still
 * encoded/decoded by the parent.</p>
 *
 * <p>This is an internal helper: the public protocol entry points remain
 * {@link CodingAgentJob#encode()} and {@link CodingAgentJob#set(String, String)},
 * which delegate to {@link #appendEncoded(StringBuilder, CodingAgentJob)} and
 * {@link #applySetting(CodingAgentJob, String, String)} respectively.</p>
 */
final class CodingAgentJobCodec {

    /**
     * Shared mapper for {@link PhaseConfigBundle} JSON serialization. NON_NULL
     * keeps wire bytes minimal — every {@link io.flowtree.jobs.agent.PhaseConfig}
     * field is independently nullable, and most jobs leave most fields unset.
     */
    private static final ObjectMapper BUNDLE_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Prevents instantiation; this class only exposes static helpers. */
    private CodingAgentJobCodec() {
    }

    /**
     * Serializes a {@link PhaseConfigBundle} to a Base64-wrapped JSON string
     * for transport in the {@code phaseConfigBundle} wire-format slot.
     * Returns {@code null} when the bundle is {@code null} or empty so callers
     * can skip emitting the key entirely.
     *
     * @param bundle the bundle to serialize; may be {@code null} or empty
     * @return Base64 of the JSON form, or {@code null} when there is nothing
     *         worth sending
     */
    static String encodePhaseConfigBundle(PhaseConfigBundle bundle) {
        if (bundle == null || bundle.isEmpty()) return null;
        try {
            return GitManagedJob.base64Encode(BUNDLE_MAPPER.writeValueAsString(bundle));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode PhaseConfigBundle", e);
        }
    }

    /**
     * Reverses {@link #encodePhaseConfigBundle(PhaseConfigBundle)}. Returns
     * {@link PhaseConfigBundle#EMPTY} for null/empty input so legacy wire
     * payloads (which never set the key) decode without throwing.
     *
     * @param value Base64-wrapped JSON produced by
     *              {@link #encodePhaseConfigBundle(PhaseConfigBundle)}, or
     *              {@code null}/empty
     * @return the deserialized bundle, never {@code null}
     */
    static PhaseConfigBundle decodePhaseConfigBundle(String value) {
        if (value == null || value.isEmpty()) return PhaseConfigBundle.EMPTY;
        try {
            return BUNDLE_MAPPER.readValue(
                    GitManagedJob.base64Decode(value), PhaseConfigBundle.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not decode PhaseConfigBundle", e);
        }
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
        // Runner identity. Model, effort, and provider are NOT separate wire
        // keys — they travel only inside phaseConfigBundle below, whose decode
        // (setPhaseConfigBundle) applies them without per-key validation.
        String defaultRunner = job.getDefaultRunner();
        if (defaultRunner != null && !AgentRunnerRegistry.CLAUDE.equals(defaultRunner)) {
            sb.append("::defaultRunner:=").append(defaultRunner);
        }
        Map<Phase, String> runners = job.getRunnerByPhase();
        if (!runners.isEmpty()) {
            sb.append("::runners:=").append(Phase.encodeRunnerMap(runners));
        }
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
        if (!job.isReviewEnabled()) {
            sb.append("::reviewEnabled:=false");
        }
        if (job.isRetrospectiveEnabled()) {
            sb.append("::retrospectiveEnabled:=true");
        }
        if (job.getMaxReviewPasses() != CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES) {
            sb.append("::maxReviewPasses:=").append(job.getMaxReviewPasses());
        }
        String postCmd = job.getPostCompletionCommand();
        if (postCmd != null && !postCmd.isEmpty()) {
            sb.append("::postCmd:=").append(GitManagedJob.base64Encode(postCmd));
            if (job.getPostCompletionWorkingDir() != null) {
                sb.append("::postCmdDir:=").append(GitManagedJob.base64Encode(job.getPostCompletionWorkingDir()));
            }
            if (job.getPostCompletionTimeoutSeconds() != PostCompletionCommandRule.DEFAULT_TIMEOUT_SECONDS) {
                sb.append("::postCmdTimeout:=").append(job.getPostCompletionTimeoutSeconds());
            }
            if (job.getMaxPostCompletionPasses() != CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES) {
                sb.append("::maxPostCmdPasses:=").append(job.getMaxPostCompletionPasses());
            }
        }
        // The bundle is the sole carrier of model, effort, and provider (both
        // the default and every per-phase override). The defaultRunner/runners
        // keys above carry only runner identity for runner-resolution callers.
        String bundleWire = encodePhaseConfigBundle(job.getPhaseConfigBundle());
        if (bundleWire != null) {
            sb.append("::phaseConfigBundle:=").append(bundleWire);
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
            case "reviewEnabled":
                job.setReviewEnabled(Boolean.parseBoolean(value));
                return true;
            // TODO(review): add case "reflectionEnabled" as a backward-compat alias for jobs serialized with the old key
            case "retrospectiveEnabled":
                job.setRetrospectiveEnabled(Boolean.parseBoolean(value));
                return true;
            case "maxReviewPasses":
                job.setMaxReviewPasses(parsePositiveOrDefault(value, CodingAgentJob.DEFAULT_MAX_REVIEW_PASSES));
                return true;
            case "postCmd":
                job.setPostCompletionCommand(GitManagedJob.base64Decode(value));
                return true;
            case "postCmdDir":
                job.setPostCompletionWorkingDir(GitManagedJob.base64Decode(value));
                return true;
            case "postCmdTimeout":
                job.setPostCompletionTimeoutSeconds(Integer.parseInt(value));
                return true;
            case "maxPostCmdPasses":
                job.setMaxPostCompletionPasses(
                        parsePositiveOrDefault(value, CodingAgentJob.DEFAULT_MAX_POST_COMPLETION_PASSES));
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
                job.setPhaseConfigBundle(decodePhaseConfigBundle(value));
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
