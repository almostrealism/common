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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable configuration for {@link GitManagedJob}.
 *
 * <p>All git-related configuration fields are consolidated here so they
 * can be set during deserialization (via the {@link Builder}) and then
 * frozen. This eliminates race conditions from setters being called
 * after {@code run()} starts and structurally prevents the recursion
 * bug that the old mutable-setter design enabled.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * GitJobConfig config = GitJobConfig.builder()
 *     .targetBranch("feature/my-work")
 *     .baseBranch("main")
 *     .repoUrl("https://github.com/owner/repo.git")
 *     .pushToOrigin(true)
 *     .build();
 * }</pre>
 *
 * @author Michael Murray
 */
public final class GitJobConfig {

    /** Default maximum file size to commit (1 MB). */
    public static final long DEFAULT_MAX_FILE_SIZE = 1024 * 1024;

    /** File patterns that are always excluded from commits. */
    public static final Set<String> DEFAULT_EXCLUDED_PATTERNS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        // Secrets and credentials
        ".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx",
        "credentials.json", "secrets.json", "**/secrets/**",

        // Build outputs and dependencies
        "target/**", "build/**", "dist/**", "out/**",
        "node_modules/**", ".gradle/**", ".m2/**",
        "*.class", "*.jar", "*.war", "*.ear",

        // IDE and OS files
        ".idea/**", ".vscode/**", "*.iml",
        ".DS_Store", "Thumbs.db",

        // Binary and media files
        "*.exe", "*.dll", "*.so", "*.dylib",
        "*.zip", "*.tar", "*.gz", "*.rar", "*.7z",
        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.ico",
        "*.mp3", "*.mp4", "*.wav", "*.avi", "*.mov",
        "*.pdf", "*.doc", "*.docx", "*.xls", "*.xlsx",

        // Database and logs
        "*.db", "*.sqlite", "*.log",

        // Hardware acceleration outputs (AR-specific)
        "Extensions/**", "*.cl", "*.metal",

        // Claude Code agent outputs and settings
        "claude-output/**", "commit.txt",
        ".claude/**", "settings.local.json"
    )));

    /** Path patterns for test/CI files protected by {@link #protectTestFiles}. */
    public static final Set<String> PROTECTED_PATH_PATTERNS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "**/src/test/**",
        "**/src/it/**",
        ".github/workflows/**",
        ".github/actions/**"
    )));

    /** Branch that the agent writes changes to. */
    private final String targetBranch;

    /** Base branch used for merge conflict detection and test file protection. */
    private final String baseBranch;

    /** Absolute path to the local git working directory. */
    private final String workingDirectory;

    /** Remote repository URL (SSH or HTTPS). */
    private final String repoUrl;

    /** Default parent path for workspace checkouts when no working directory is set. */
    private final String defaultWorkspacePath;

    /** Maximum file size in bytes for staging. */
    private final long maxFileSizeBytes;

    /**
     * Base set of glob patterns always excluded from commits (populated from
     * {@link #DEFAULT_EXCLUDED_PATTERNS} by the {@link Builder}).
     */
    private final Set<String> excludedPatterns;

    /** Additional exclusion patterns on top of the base set. */
    private final Set<String> additionalExcludedPatterns;

    /**
     * Whether staged changes are pushed to {@code origin} after committing.
     */
    private final boolean pushToOrigin;

    /**
     * Whether to create the target branch from the base branch if it does
     * not yet exist on the remote.
     */
    private final boolean createBranchIfMissing;

    /**
     * When {@code true}, git operations are simulated but no commit or push
     * is performed. Useful for testing.
     */
    private final boolean dryRun;

    /**
     * When {@code true}, files matching {@link #PROTECTED_PATH_PATTERNS} that
     * exist on the base branch are blocked from staging.
     */
    private final boolean protectTestFiles;

    /** Git author/committer name applied via environment variables. */
    private final String gitUserName;

    /** Git author/committer email applied via environment variables. */
    private final String gitUserEmail;

    /** Workstream URL for status callbacks and tool authentication. */
    private final String workstreamUrl;

    /**
     * Private constructor — use {@link #builder()} to create instances.
     *
     * @param builder the populated builder
     */
    private GitJobConfig(Builder builder) {
        this.targetBranch = builder.targetBranch;
        this.baseBranch = builder.baseBranch;
        this.workingDirectory = builder.workingDirectory;
        this.repoUrl = builder.repoUrl;
        this.defaultWorkspacePath = builder.defaultWorkspacePath;
        this.maxFileSizeBytes = builder.maxFileSizeBytes;
        this.excludedPatterns = Collections.unmodifiableSet(new HashSet<>(builder.excludedPatterns));
        this.additionalExcludedPatterns = Collections.unmodifiableSet(new HashSet<>(builder.additionalExcludedPatterns));
        this.pushToOrigin = builder.pushToOrigin;
        this.createBranchIfMissing = builder.createBranchIfMissing;
        this.dryRun = builder.dryRun;
        this.protectTestFiles = builder.protectTestFiles;
        this.gitUserName = builder.gitUserName;
        this.gitUserEmail = builder.gitUserEmail;
        this.workstreamUrl = builder.workstreamUrl;
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
     * Returns whether git operations should be performed.
     *
     * <p>Git operations are enabled only when a non-empty target branch is
     * configured. Jobs that do not need git management leave this field null.</p>
     *
     * @return {@code true} if a target branch is set
     */
    public boolean isGitEnabled() {
        return targetBranch != null && !targetBranch.isEmpty();
    }

    /**
     * Returns the combined set of base and additional excluded patterns.
     *
     * <p>The returned set is the union of {@link #getExcludedPatterns()} and
     * {@link #getAdditionalExcludedPatterns()}, returned as an unmodifiable
     * view.</p>
     *
     * @return all exclusion patterns
     */
    public Set<String> getAllExcludedPatterns() {
        Set<String> all = new HashSet<>(excludedPatterns);
        all.addAll(additionalExcludedPatterns);
        return Collections.unmodifiableSet(all);
    }

    /**
     * Returns the branch that the agent writes changes to.
     *
     * @return the target branch name, or {@code null} if not set
     */
    public String getTargetBranch() { return targetBranch; }

    /**
     * Returns the base branch used for merge conflict detection and test file
     * protection checks.
     *
     * @return the base branch name
     */
    public String getBaseBranch() { return baseBranch; }

    /**
     * Returns the absolute path to the local git working directory.
     *
     * @return the working directory path, or {@code null} if not set
     */
    public String getWorkingDirectory() { return workingDirectory; }

    /**
     * Returns the remote repository URL.
     *
     * @return the repository URL (SSH or HTTPS format), or {@code null}
     */
    public String getRepoUrl() { return repoUrl; }

    /**
     * Returns the default parent path for workspace checkouts.
     *
     * @return the default workspace path, or {@code null}
     */
    public String getDefaultWorkspacePath() { return defaultWorkspacePath; }

    /**
     * Returns the maximum file size in bytes for staging.
     *
     * @return the maximum file size threshold
     */
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }

    /**
     * Returns the base set of glob patterns always excluded from commits.
     *
     * @return an unmodifiable set of exclusion patterns
     */
    public Set<String> getExcludedPatterns() { return excludedPatterns; }

    /**
     * Returns the additional exclusion patterns added on top of the base set.
     *
     * @return an unmodifiable set of additional exclusion patterns
     */
    public Set<String> getAdditionalExcludedPatterns() { return additionalExcludedPatterns; }

    /**
     * Returns whether staged changes are pushed to {@code origin} after
     * committing.
     *
     * @return {@code true} if push to origin is enabled
     */
    public boolean isPushToOrigin() { return pushToOrigin; }

    /**
     * Returns whether the target branch should be created from the base branch
     * if it does not yet exist on the remote.
     *
     * @return {@code true} if auto-creation of the branch is enabled
     */
    public boolean isCreateBranchIfMissing() { return createBranchIfMissing; }

    /**
     * Returns whether git operations are run in dry-run mode.
     *
     * <p>When {@code true}, no commit or push is performed. Useful for testing
     * the staging logic without modifying the remote repository.</p>
     *
     * @return {@code true} if dry-run mode is active
     */
    public boolean isDryRun() { return dryRun; }

    /**
     * Returns whether test file protection is active.
     *
     * @return {@code true} if files matching {@link #PROTECTED_PATH_PATTERNS}
     *         on the base branch are blocked from staging
     */
    public boolean isProtectTestFiles() { return protectTestFiles; }

    /**
     * Returns the git author and committer name.
     *
     * @return the git user name, or {@code null} if not configured
     */
    public String getGitUserName() { return gitUserName; }

    /**
     * Returns the git author and committer email.
     *
     * @return the git user email, or {@code null} if not configured
     */
    public String getGitUserEmail() { return gitUserEmail; }

    /**
     * Returns the workstream URL for status callbacks and tool authentication.
     *
     * @return the workstream URL, or {@code null} if not configured
     */
    public String getWorkstreamUrl() { return workstreamUrl; }

    /**
     * Returns a concise string representation of the key configuration fields.
     *
     * @return a summary string
     */
    @Override
    public String toString() {
        return "GitJobConfig{" +
            "targetBranch='" + targetBranch + '\'' +
            ", baseBranch='" + baseBranch + '\'' +
            ", repoUrl='" + repoUrl + '\'' +
            ", pushToOrigin=" + pushToOrigin +
            ", protectTestFiles=" + protectTestFiles +
            '}';
    }

    /**
     * Mutable builder for {@link GitJobConfig}.
     *
     * <p>During deserialization, the {@code set()} method populates this
     * builder. After all properties are loaded, {@link #build()} produces
     * the immutable config. Setters on the Factory also populate this
     * builder, never mutable job fields.</p>
     */
    public static final class Builder {

        /** @see GitJobConfig#targetBranch */
        private String targetBranch;

        /** @see GitJobConfig#baseBranch */
        private String baseBranch = "master";

        /** @see GitJobConfig#workingDirectory */
        private String workingDirectory;

        /** @see GitJobConfig#repoUrl */
        private String repoUrl;

        /** @see GitJobConfig#defaultWorkspacePath */
        private String defaultWorkspacePath;

        /** @see GitJobConfig#maxFileSizeBytes */
        private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE;

        /** @see GitJobConfig#excludedPatterns */
        private Set<String> excludedPatterns = new HashSet<>(DEFAULT_EXCLUDED_PATTERNS);

        /** @see GitJobConfig#additionalExcludedPatterns */
        private Set<String> additionalExcludedPatterns = new HashSet<>();

        /** @see GitJobConfig#pushToOrigin */
        private boolean pushToOrigin = true;

        /** @see GitJobConfig#createBranchIfMissing */
        private boolean createBranchIfMissing = true;

        /** @see GitJobConfig#dryRun */
        private boolean dryRun = false;

        /** @see GitJobConfig#protectTestFiles */
        private boolean protectTestFiles = false;

        /** @see GitJobConfig#gitUserName */
        private String gitUserName;

        /** @see GitJobConfig#gitUserEmail */
        private String gitUserEmail;

        /** @see GitJobConfig#workstreamUrl */
        private String workstreamUrl;

        /**
         * Private constructor — use {@link GitJobConfig#builder()} to obtain instances.
         */
        private Builder() { }

        /**
         * Sets the branch the agent writes changes to.
         *
         * @param targetBranch the target branch name
         * @return this builder
         */
        public Builder targetBranch(String targetBranch) {
            this.targetBranch = targetBranch;
            return this;
        }

        /**
         * Sets the base branch for merge conflict detection and test file
         * protection (default: {@code "master"}).
         *
         * @param baseBranch the base branch name
         * @return this builder
         */
        public Builder baseBranch(String baseBranch) {
            this.baseBranch = baseBranch;
            return this;
        }

        /**
         * Sets the absolute path to the local git working directory.
         *
         * @param workingDirectory the working directory path
         * @return this builder
         */
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Sets the remote repository URL (SSH or HTTPS).
         *
         * @param repoUrl the repository URL
         * @return this builder
         */
        public Builder repoUrl(String repoUrl) {
            this.repoUrl = repoUrl;
            return this;
        }

        /**
         * Sets the default parent path for workspace checkouts.
         *
         * @param defaultWorkspacePath the default workspace path
         * @return this builder
         */
        public Builder defaultWorkspacePath(String defaultWorkspacePath) {
            this.defaultWorkspacePath = defaultWorkspacePath;
            return this;
        }

        /**
         * Sets the maximum file size in bytes for staging.
         *
         * @param maxFileSizeBytes the size threshold
         * @return this builder
         */
        public Builder maxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
            return this;
        }

        /**
         * Replaces the base exclusion pattern set with the provided patterns.
         *
         * @param excludedPatterns the replacement exclusion patterns
         * @return this builder
         */
        public Builder excludedPatterns(Set<String> excludedPatterns) {
            this.excludedPatterns = new HashSet<>(excludedPatterns);
            return this;
        }

        /**
         * Replaces the additional exclusion pattern set with the provided patterns.
         *
         * @param additionalExcludedPatterns the replacement additional patterns
         * @return this builder
         */
        public Builder additionalExcludedPatterns(Set<String> additionalExcludedPatterns) {
            this.additionalExcludedPatterns = new HashSet<>(additionalExcludedPatterns);
            return this;
        }

        /**
         * Appends one or more patterns to the additional exclusion set.
         *
         * @param patterns the patterns to add
         * @return this builder
         */
        public Builder addExcludedPatterns(String... patterns) {
            this.additionalExcludedPatterns.addAll(Arrays.asList(patterns));
            return this;
        }

        /**
         * Removes all patterns from the base exclusion set, allowing a
         * fully custom set to be specified via {@link #excludedPatterns(Set)}.
         *
         * @return this builder
         */
        public Builder clearDefaultExcludedPatterns() {
            this.excludedPatterns.clear();
            return this;
        }

        /**
         * Sets whether staged changes are pushed to {@code origin} after
         * committing (default: {@code true}).
         *
         * @param pushToOrigin {@code true} to enable push
         * @return this builder
         */
        public Builder pushToOrigin(boolean pushToOrigin) {
            this.pushToOrigin = pushToOrigin;
            return this;
        }

        /**
         * Sets whether the target branch should be created from the base
         * branch if absent on the remote (default: {@code true}).
         *
         * @param createBranchIfMissing {@code true} to enable auto-creation
         * @return this builder
         */
        public Builder createBranchIfMissing(boolean createBranchIfMissing) {
            this.createBranchIfMissing = createBranchIfMissing;
            return this;
        }

        /**
         * Sets whether git operations run in dry-run mode (default: {@code false}).
         *
         * @param dryRun {@code true} to simulate without committing or pushing
         * @return this builder
         */
        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        /**
         * Sets whether test file protection is active (default: {@code false}).
         *
         * @param protectTestFiles {@code true} to block protected test files
         * @return this builder
         */
        public Builder protectTestFiles(boolean protectTestFiles) {
            this.protectTestFiles = protectTestFiles;
            return this;
        }

        /**
         * Sets the git author and committer name.
         *
         * @param gitUserName the name to use for commits
         * @return this builder
         */
        public Builder gitUserName(String gitUserName) {
            this.gitUserName = gitUserName;
            return this;
        }

        /**
         * Sets the git author and committer email.
         *
         * @param gitUserEmail the email to use for commits
         * @return this builder
         */
        public Builder gitUserEmail(String gitUserEmail) {
            this.gitUserEmail = gitUserEmail;
            return this;
        }

        /**
         * Sets the workstream URL for status callbacks and tool authentication.
         *
         * @param workstreamUrl the workstream URL
         * @return this builder
         */
        public Builder workstreamUrl(String workstreamUrl) {
            this.workstreamUrl = workstreamUrl;
            return this;
        }

        /**
         * Builds the immutable {@link GitJobConfig}.
         *
         * @return the new configuration instance
         */
        public GitJobConfig build() {
            return new GitJobConfig(this);
        }
    }
}
