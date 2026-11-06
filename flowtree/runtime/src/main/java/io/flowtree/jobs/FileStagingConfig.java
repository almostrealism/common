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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable configuration for file staging guardrails.
 *
 * <p>Encapsulates the rules that {@link FileStager} applies when evaluating
 * which files should be staged: maximum file size, glob patterns to exclude,
 * protected path patterns for test/CI files, and whether test file protection
 * is active.</p>
 *
 * <p>All {@link Set} fields are stored as unmodifiable copies to guarantee
 * immutability after construction.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FileStagingConfig config = FileStagingConfig.builder()
 *     .maxFileSizeBytes(2 * 1024 * 1024)
 *     .protectTestFiles(true)
 *     .baseBranch("main")
 *     .build();
 * }</pre>
 *
 * @author Michael Murray
 * @see FileStager
 * @see StagingResult
 */
public final class FileStagingConfig {

    /** Default maximum file size (1 MB). */
    public static final long DEFAULT_MAX_FILE_SIZE = 1024 * 1024;

    /** Maximum file size threshold in bytes; files larger than this are skipped. */
    private final long maxFileSizeBytes;

    /** Glob patterns that unconditionally exclude a file from staging. */
    private final Set<String> excludedPatterns;

    /** Glob patterns identifying protected test and CI files. */
    private final Set<String> protectedPathPatterns;

    /** Whether test file protection is active. */
    private final boolean protectTestFiles;

    /** Whether CI/workflow file protection is active. */
    private final boolean protectCiFiles;

    /** Base branch used for test file existence checks. */
    private final String baseBranch;

    /**
     * The base-branch commit the harness merged in and left in progress, or
     * {@code null}; see {@link #getMergeParent()}.
     */
    private final String mergeParent;

    /**
     * Private constructor — use {@link #builder()} to create instances.
     *
     * @param builder the populated builder
     */
    private FileStagingConfig(Builder builder) {
        this.maxFileSizeBytes = builder.maxFileSizeBytes;
        this.excludedPatterns = Collections.unmodifiableSet(new HashSet<>(builder.excludedPatterns));
        this.protectedPathPatterns = Collections.unmodifiableSet(new HashSet<>(builder.protectedPathPatterns));
        this.protectTestFiles = builder.protectTestFiles;
        this.protectCiFiles = builder.protectCiFiles;
        this.baseBranch = builder.baseBranch;
        this.mergeParent = builder.mergeParent;
    }

    /**
     * Returns a new builder with default values.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds the staging configuration that governs which of {@code job}'s
     * changed files may be staged. This is the single source of truth for the
     * two locks so that {@link GitCommitHandler} and
     * {@link GitManagedJob#previewStaging()} cannot drift apart: a preview that
     * showed a file as stageable while the commit silently dropped it would
     * cost the agent its correction turn.
     *
     * <p>The test-file lock is gated on BOTH the {@code protectTestFiles} flag
     * AND {@code sensitiveFileProtectionEnabled} — ANDed, not ORed, so a
     * default job (both true) gets the full protection and either being off
     * disables it. The CI file lock is independent of the test lock: it is on
     * for every job whose sensitive-file protection is enabled, except one on a
     * {@code ci/...} branch, matching the exemptions
     * {@code check-ci-file-lock.sh} applies in CI.</p>
     *
     * @param job the job whose staging configuration is being built
     * @return the immutable staging configuration for the job
     */
    public static FileStagingConfig forJob(GitManagedJob job) {
        return builder()
                .excludedPatterns(job.getAllExcludedPatterns())
                .protectedPathPatterns(GitJobConfig.PROTECTED_PATH_PATTERNS)
                .protectTestFiles(job.isProtectTestFiles()
                        && job.isSensitiveFileProtectionEnabled())
                .protectCiFiles(job.isSensitiveFileProtectionEnabled()
                        && !isCiBranch(job.getTargetBranch()))
                .baseBranch(job.getBaseBranch())
                .maxFileSizeBytes(job.getMaxFileSizeBytes())
                .mergeParent(job.getHarnessMergeParent())
                .build();
    }

    /**
     * Returns whether {@code branch} is a CI branch ({@code ci/...}), whose
     * declared subject is the pipeline itself and which is therefore exempt
     * from the CI file lock {@link #isProtectCiFiles()} applies.
     *
     * <p>The harness applies the repository's CI file lock to every job,
     * independently of the job's test lock: a CI/workflow file (branch-new or
     * pre-existing) is not staged unless the controller authorised the job to
     * change sensitive files (its commit then carries the signed bypass
     * trailer) or the job works on a CI branch. These are the same exemptions
     * {@code tools/ci/agent-protection/check-ci-file-lock.sh} applies in CI, so
     * an edit the harness would stage is never one the pipeline rejects.</p>
     *
     * @param branch the target branch, or {@code null}
     * @return {@code true} when the branch name starts with {@code ci/}
     */
    public static boolean isCiBranch(String branch) {
        return branch != null && branch.startsWith("ci/");
    }

    /**
     * Returns the maximum file size in bytes. Files exceeding this
     * threshold are skipped during staging.
     *
     * @return the maximum file size in bytes
     */
    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    /**
     * Returns the set of glob patterns used to exclude files from staging.
     *
     * @return an unmodifiable set of exclusion glob patterns
     */
    public Set<String> getExcludedPatterns() {
        return excludedPatterns;
    }

    /**
     * Returns the set of glob patterns identifying protected test/CI files.
     *
     * @return an unmodifiable set of protected path glob patterns
     */
    public Set<String> getProtectedPathPatterns() {
        return protectedPathPatterns;
    }

    /**
     * Returns whether test file protection is active. When enabled,
     * files matching {@link #getProtectedPathPatterns()} that also
     * exist on the base branch are blocked from staging.
     *
     * @return true if test file protection is active
     */
    public boolean isProtectTestFiles() {
        return protectTestFiles;
    }

    /**
     * Returns whether CI/workflow file protection is active. When enabled,
     * a CI/workflow file ({@code .github/workflows/**}, {@code .github/actions/**},
     * {@code tools/ci/**}) is blocked from staging whether or not it exists
     * at the merge-base, since the pipeline's CI file lock rejects a
     * branch-new workflow file as well as an edit to an existing one. This
     * is the harness side of the repository's CI file lock and is
     * independent of {@link #isProtectTestFiles()}: a job's test lock says
     * nothing about whether it may change the pipeline.
     *
     * @return true if CI/workflow file protection is active
     */
    public boolean isProtectCiFiles() {
        return protectCiFiles;
    }

    /**
     * Returns whether this configuration blocks {@code file} whole-file as CI
     * configuration under the CI file lock (see {@link #isProtectCiFiles()}).
     * In production {@link #isProtectCiFiles()} is the sole gate; a CI file
     * named by {@link #getProtectedPathPatterns()} is also locked while the
     * test lock is on.
     *
     * @param file the repository-relative path
     * @return {@code true} when the path is CI configuration this configuration locks
     */
    public boolean isCiLocked(String file) {
        return isCiWorkflowFile(file) && (protectCiFiles
                || (protectTestFiles && FileStager.matchesAnyPattern(file, protectedPathPatterns)));
    }

    /**
     * Returns whether {@code file} falls under this configuration's test lock:
     * the test lock is on and the path, which is not CI configuration, matches
     * {@link #getProtectedPathPatterns()}. Whether a test-locked file may still
     * be staged depends on its base-branch history; see {@link FileStager}.
     *
     * @param file the repository-relative path
     * @return {@code true} when the test lock applies to the path
     */
    public boolean isTestLocked(String file) {
        return protectTestFiles && !isCiWorkflowFile(file)
                && FileStager.matchesAnyPattern(file, protectedPathPatterns);
    }

    /**
     * Returns whether {@code file} is CI configuration: a GitHub Actions
     * workflow or action definition ({@code .github/workflows/**},
     * {@code .github/actions/**}) or CI tooling ({@code tools/ci/**}) — the
     * paths the repository's CI file lock covers.
     *
     * <p>These paths keep whole-file protection rather than the test-method
     * granularity {@link TestMethodProtection} applies to Java test sources:
     * a workflow file has no "test method" structure to reason about, and
     * CI/workflow configuration is locked in full by design (see
     * {@code tools/ci/agent-protection/check-ci-file-lock.sh}).</p>
     *
     * @param file the file path to test
     * @return true if the path is a protected CI/workflow path
     */
    private static boolean isCiWorkflowFile(String file) {
        return FileStager.matchesGlobPattern(file, ".github/workflows/**")
                || FileStager.matchesGlobPattern(file, ".github/actions/**")
                || FileStager.matchesGlobPattern(file, "tools/ci/**");
    }

    /**
     * Returns the base branch used for test file existence checks.
     *
     * @return the base branch name
     */
    public String getBaseBranch() {
        return baseBranch;
    }

    /**
     * Returns the base-branch commit the harness merged into the target branch
     * and left in progress for the agent, or {@code null} when it started no
     * such merge.
     *
     * <p>While that merge is still the one in progress, {@link FileStager}
     * treats content identical to this commit as carried in by the merge
     * rather than written by the agent, and judges protected test methods
     * against this commit rather than the older merge-base.</p>
     *
     * @return the merge parent's full SHA, or {@code null}
     */
    public String getMergeParent() {
        return mergeParent;
    }

    /**
     * Returns a concise string representation of this configuration showing
     * pattern counts rather than the full pattern sets.
     *
     * @return a summary string
     */
    @Override
    public String toString() {
        return "FileStagingConfig{" +
            "maxFileSizeBytes=" + maxFileSizeBytes +
            ", excludedPatterns=" + excludedPatterns.size() +
            ", protectedPathPatterns=" + protectedPathPatterns.size() +
            ", protectTestFiles=" + protectTestFiles +
            ", protectCiFiles=" + protectCiFiles +
            ", baseBranch='" + baseBranch + '\'' +
            ", mergeParent=" + mergeParent +
            '}';
    }

    /**
     * Mutable builder for {@link FileStagingConfig}.
     *
     * <p>Provides a fluent API to configure file staging guardrails
     * before producing an immutable configuration via {@link #build()}.</p>
     */
    public static final class Builder {

        /** @see FileStagingConfig#maxFileSizeBytes */
        private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE;

        /** @see FileStagingConfig#excludedPatterns */
        private Set<String> excludedPatterns = new HashSet<>();

        /** @see FileStagingConfig#protectedPathPatterns */
        private Set<String> protectedPathPatterns = new HashSet<>();

        /** @see FileStagingConfig#protectTestFiles */
        private boolean protectTestFiles = false;

        /** @see FileStagingConfig#protectCiFiles */
        private boolean protectCiFiles = false;

        /** @see FileStagingConfig#baseBranch */
        private String baseBranch = "master";

        /** @see FileStagingConfig#mergeParent */
        private String mergeParent;

        /**
         * Private constructor — use {@link FileStagingConfig#builder()} to obtain instances.
         */
        private Builder() { }

        /**
         * Sets the maximum file size in bytes.
         *
         * @param maxFileSizeBytes the maximum size threshold
         * @return this builder
         */
        public Builder maxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
            return this;
        }

        /**
         * Sets the glob patterns used to exclude files from staging.
         *
         * @param excludedPatterns the exclusion patterns
         * @return this builder
         */
        public Builder excludedPatterns(Set<String> excludedPatterns) {
            this.excludedPatterns = new HashSet<>(excludedPatterns);
            return this;
        }

        /**
         * Sets the glob patterns identifying protected test/CI files.
         *
         * @param protectedPathPatterns the protected path patterns
         * @return this builder
         */
        public Builder protectedPathPatterns(Set<String> protectedPathPatterns) {
            this.protectedPathPatterns = new HashSet<>(protectedPathPatterns);
            return this;
        }

        /**
         * Sets whether test file protection is active.
         *
         * @param protectTestFiles true to enable protection
         * @return this builder
         */
        public Builder protectTestFiles(boolean protectTestFiles) {
            this.protectTestFiles = protectTestFiles;
            return this;
        }

        /**
         * Sets whether CI/workflow file protection is active.
         *
         * @param protectCiFiles true to block staging of CI/workflow files,
         *                       whether branch-new or pre-existing
         * @return this builder
         */
        public Builder protectCiFiles(boolean protectCiFiles) {
            this.protectCiFiles = protectCiFiles;
            return this;
        }

        /**
         * Sets the base branch for test file existence checks.
         *
         * @param baseBranch the base branch name (default: "master")
         * @return this builder
         */
        public Builder baseBranch(String baseBranch) {
            this.baseBranch = baseBranch;
            return this;
        }

        /**
         * Sets the base-branch commit the harness merged in and left in
         * progress for the agent.
         *
         * @param mergeParent the merge parent's full SHA, or {@code null}
         *                    when no harness merge is in progress
         * @return this builder
         */
        public Builder mergeParent(String mergeParent) {
            this.mergeParent = mergeParent;
            return this;
        }

        /**
         * Builds the immutable configuration.
         *
         * @return a new {@link FileStagingConfig} instance
         */
        public FileStagingConfig build() {
            return new FileStagingConfig(this);
        }
    }
}
