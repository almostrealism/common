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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wire-format codec for the fields owned by {@link GitManagedJob}.
 *
 * <p>Owns the serialization layout for the git-management fields (branch, repo
 * URL, working directory, staging guardrails, git identity, required labels,
 * etc.) so {@link GitManagedJob} itself is not weighed down by encode/decode
 * boilerplate. This mirrors {@link CodingAgentJobCodec}, which does the same for
 * the coding-agent-specific fields layered on top.</p>
 *
 * <p>This is an internal helper: the public protocol entry points remain
 * {@link GitManagedJob#encode()} and {@link GitManagedJob#set(String, String)},
 * which delegate to {@link #appendEncoded(StringBuilder, GitManagedJob)} and
 * {@link #applySetting(GitManagedJob, String, String)} respectively. The job
 * class retains responsibility for the leading class-name token and the
 * environment-property tail, which span the whole job hierarchy.</p>
 *
 * @author Michael Murray
 */
final class GitManagedJobCodec {

    /** Prevents instantiation; this class only exposes static helpers. */
    private GitManagedJobCodec() {
    }

    /**
     * Appends the {@code ::key:=value} tokens for every {@link GitManagedJob}
     * field to {@code sb}. Only non-default values are emitted to keep the
     * encoded string compact. String values are Base64-encoded via
     * {@link GitManagedJob#base64Encode(String)} to avoid delimiter collisions.
     *
     * @param sb  the builder accumulating the encoded job string
     * @param job the job whose base fields are serialized
     */
    static void appendEncoded(StringBuilder sb, GitManagedJob job) {
        sb.append("::taskId:=").append(job.getTaskId());
        if (job.getTargetBranch() != null) {
            sb.append("::branch:=").append(GitManagedJob.base64Encode(job.getTargetBranch()));
        }
        if (job.getBaseBranch() != null && !"master".equals(job.getBaseBranch())) {
            sb.append("::baseBranch:=").append(GitManagedJob.base64Encode(job.getBaseBranch()));
        }
        if (job.getWorkingDirectory() != null) {
            sb.append("::workDir:=").append(GitManagedJob.base64Encode(job.getWorkingDirectory()));
        }
        if (job.getRepoUrl() != null) {
            sb.append("::repoUrl:=").append(GitManagedJob.base64Encode(job.getRepoUrl()));
        }
        if (job.getDefaultWorkspacePath() != null) {
            sb.append("::defaultWsPath:=").append(GitManagedJob.base64Encode(job.getDefaultWorkspacePath()));
        }
        sb.append("::maxFileSize:=").append(job.getMaxFileSizeBytes());
        sb.append("::push:=").append(job.isPushToOrigin());
        sb.append("::createBranch:=").append(job.isCreateBranchIfMissing());
        sb.append("::dryRun:=").append(job.isDryRun());
        sb.append("::protectTests:=").append(job.isProtectTestFiles());
        if (job.getGitUserName() != null) {
            sb.append("::gitUserName:=").append(GitManagedJob.base64Encode(job.getGitUserName()));
        }
        if (job.getGitUserEmail() != null) {
            sb.append("::gitUserEmail:=").append(GitManagedJob.base64Encode(job.getGitUserEmail()));
        }
        if (job.getWorkstreamUrl() != null) {
            sb.append("::workstreamUrl:=").append(GitManagedJob.base64Encode(job.getWorkstreamUrl()));
        }
        if (job.getDependentRepos() != null && !job.getDependentRepos().isEmpty()) {
            sb.append("::depRepos:=").append(GitManagedJob.base64Encode(String.join(",", job.getDependentRepos())));
        }
        for (Map.Entry<String, String> entry : job.getRequiredLabels().entrySet()) {
            sb.append("::req.").append(entry.getKey()).append(":=").append(entry.getValue());
        }
    }

    /**
     * Applies a single decoded {@code key:=value} pair to {@code job}.
     *
     * @param job   the job to populate
     * @param key   the property key from the encoded string
     * @param value the property value (Base64-encoded for string fields)
     * @return {@code true} if this codec recognized and consumed the key,
     *         {@code false} so the caller can fall back to environment-property
     *         handling for unknown keys
     */
    static boolean applySetting(GitManagedJob job, String key, String value) {
        switch (key) {
            case "taskId":
                job.setTaskId(value);
                return true;
            case "workDir":
                job.setWorkingDirectory(GitManagedJob.base64Decode(value));
                return true;
            case "repoUrl":
                job.setRepoUrl(GitManagedJob.base64Decode(value));
                return true;
            case "defaultWsPath":
                job.setDefaultWorkspacePath(GitManagedJob.base64Decode(value));
                return true;
            case "branch":
                job.setTargetBranch(GitManagedJob.base64Decode(value));
                return true;
            case "baseBranch":
                job.setBaseBranch(GitManagedJob.base64Decode(value));
                return true;
            case "push":
                job.setPushToOrigin(Boolean.parseBoolean(value));
                return true;
            case "workstreamUrl":
                job.setWorkstreamUrl(GitManagedJob.base64Decode(value));
                return true;
            case "gitUserName":
                job.setGitUserName(GitManagedJob.base64Decode(value));
                return true;
            case "gitUserEmail":
                job.setGitUserEmail(GitManagedJob.base64Decode(value));
                return true;
            case "protectTests":
                job.setProtectTestFiles(Boolean.parseBoolean(value));
                return true;
            case "maxFileSize":
                job.setMaxFileSizeBytes(Long.parseLong(value));
                return true;
            case "createBranch":
                job.setCreateBranchIfMissing(Boolean.parseBoolean(value));
                return true;
            case "dryRun":
                job.setDryRun(Boolean.parseBoolean(value));
                return true;
            case "depRepos":
                job.setDependentRepos(parseDependentRepos(GitManagedJob.base64Decode(value)));
                return true;
            default:
                if (key.startsWith("req.")) {
                    job.setRequiredLabel(key.substring(4), value);
                    return true;
                }
                return false;
        }
    }

    /**
     * Parses the comma-separated dependent-repo list, dropping blank entries.
     *
     * @param decoded the decoded {@code depRepos} value
     * @return the list of dependent repository URLs
     */
    private static List<String> parseDependentRepos(String decoded) {
        List<String> repoList = new ArrayList<>();
        for (String repo : decoded.split(",")) {
            String trimmed = repo.trim();
            if (!trimmed.isEmpty()) {
                repoList.add(trimmed);
            }
        }
        return repoList;
    }
}
