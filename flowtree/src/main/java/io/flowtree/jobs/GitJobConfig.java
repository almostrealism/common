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

    private final String targetBranch;
    private final String baseBranch;
    private final String workingDirectory;
    private final String repoUrl;
    private final String defaultWorkspacePath;
    private final long maxFileSizeBytes;
    private final Set<String> excludedPatterns;
    private final Set<String> additionalExcludedPatterns;
    private final boolean pushToOrigin;
    private final boolean createBranchIfMissing;
    private final boolean dryRun;
    private final boolean protectTestFiles;
    private final String gitUserName;
    private final String gitUserEmail;
    private final String workstreamUrl;

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
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether git operations should be performed.
     * Git operations are skipped when no target branch is configured.
     */
    public boolean isGitEnabled() {
        return targetBranch != null && !targetBranch.isEmpty();
    }

    /**
     * Returns the combined set of all excluded patterns
     * (default + additional).
     */
    public Set<String> getAllExcludedPatterns() {
        Set<String> all = new HashSet<>(excludedPatterns);
        all.addAll(additionalExcludedPatterns);
        return Collections.unmodifiableSet(all);
    }

    public String getTargetBranch() { return targetBranch; }
    public String getBaseBranch() { return baseBranch; }
    public String getWorkingDirectory() { return workingDirectory; }
    public String getRepoUrl() { return repoUrl; }
    public String getDefaultWorkspacePath() { return defaultWorkspacePath; }
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public Set<String> getExcludedPatterns() { return excludedPatterns; }
    public Set<String> getAdditionalExcludedPatterns() { return additionalExcludedPatterns; }
    public boolean isPushToOrigin() { return pushToOrigin; }
    public boolean isCreateBranchIfMissing() { return createBranchIfMissing; }
    public boolean isDryRun() { return dryRun; }
    public boolean isProtectTestFiles() { return protectTestFiles; }
    public String getGitUserName() { return gitUserName; }
    public String getGitUserEmail() { return gitUserEmail; }
    public String getWorkstreamUrl() { return workstreamUrl; }

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
        private String targetBranch;
        private String baseBranch = "master";
        private String workingDirectory;
        private String repoUrl;
        private String defaultWorkspacePath;
        private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE;
        private Set<String> excludedPatterns = new HashSet<>(DEFAULT_EXCLUDED_PATTERNS);
        private Set<String> additionalExcludedPatterns = new HashSet<>();
        private boolean pushToOrigin = true;
        private boolean createBranchIfMissing = true;
        private boolean dryRun = false;
        private boolean protectTestFiles = false;
        private String gitUserName;
        private String gitUserEmail;
        private String workstreamUrl;

        private Builder() { }

        public Builder targetBranch(String targetBranch) {
            this.targetBranch = targetBranch;
            return this;
        }

        public Builder baseBranch(String baseBranch) {
            this.baseBranch = baseBranch;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder repoUrl(String repoUrl) {
            this.repoUrl = repoUrl;
            return this;
        }

        public Builder defaultWorkspacePath(String defaultWorkspacePath) {
            this.defaultWorkspacePath = defaultWorkspacePath;
            return this;
        }

        public Builder maxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
            return this;
        }

        public Builder excludedPatterns(Set<String> excludedPatterns) {
            this.excludedPatterns = new HashSet<>(excludedPatterns);
            return this;
        }

        public Builder additionalExcludedPatterns(Set<String> additionalExcludedPatterns) {
            this.additionalExcludedPatterns = new HashSet<>(additionalExcludedPatterns);
            return this;
        }

        public Builder addExcludedPatterns(String... patterns) {
            this.additionalExcludedPatterns.addAll(Arrays.asList(patterns));
            return this;
        }

        public Builder clearDefaultExcludedPatterns() {
            this.excludedPatterns.clear();
            return this;
        }

        public Builder pushToOrigin(boolean pushToOrigin) {
            this.pushToOrigin = pushToOrigin;
            return this;
        }

        public Builder createBranchIfMissing(boolean createBranchIfMissing) {
            this.createBranchIfMissing = createBranchIfMissing;
            return this;
        }

        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public Builder protectTestFiles(boolean protectTestFiles) {
            this.protectTestFiles = protectTestFiles;
            return this;
        }

        public Builder gitUserName(String gitUserName) {
            this.gitUserName = gitUserName;
            return this;
        }

        public Builder gitUserEmail(String gitUserEmail) {
            this.gitUserEmail = gitUserEmail;
            return this;
        }

        public Builder workstreamUrl(String workstreamUrl) {
            this.workstreamUrl = workstreamUrl;
            return this;
        }

        /**
         * Builds the immutable configuration.
         */
        public GitJobConfig build() {
            return new GitJobConfig(this);
        }
    }
}
