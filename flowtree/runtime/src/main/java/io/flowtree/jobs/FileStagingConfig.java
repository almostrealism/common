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

    /** Base branch used for test file existence checks. */
    private final String baseBranch;

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
        this.baseBranch = builder.baseBranch;
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
     * Returns the base branch used for test file existence checks.
     *
     * @return the base branch name
     */
    public String getBaseBranch() {
        return baseBranch;
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
            ", baseBranch='" + baseBranch + '\'' +
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

        /** @see FileStagingConfig#baseBranch */
        private String baseBranch = "master";

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
         * Builds the immutable configuration.
         *
         * @return a new {@link FileStagingConfig} instance
         */
        public FileStagingConfig build() {
            return new FileStagingConfig(this);
        }
    }
}
