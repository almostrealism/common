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

import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stateless utility for evaluating which changed files should be staged
 * for commit, applying a series of configurable guardrails.
 *
 * <p>This class does NOT perform any git operations (no {@code git add}).
 * It only classifies files into "staged" (passed all guardrails) and
 * "skipped" (failed a guardrail) lists. The caller is responsible for
 * actually staging the approved files.</p>
 *
 * <h2>Guardrails (applied in order)</h2>
 * <ol>
 *   <li><b>Pattern exclusion</b> -- files matching any excluded glob pattern
 *       are skipped.</li>
 *   <li><b>Test file protection</b> -- if enabled, files matching protected
 *       path patterns that exist on the base branch are blocked.</li>
 *   <li><b>File size</b> -- files exceeding the maximum size threshold are
 *       skipped (deleted files are exempt).</li>
 *   <li><b>Binary detection</b> -- files with more than 10% null bytes in
 *       the first 8000 bytes are skipped (deleted files are exempt).</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FileStager stager = new FileStager();
 * StagingResult result = stager.evaluateFiles(changedFiles, config,
 *     workingDirectory, gitOps);
 *
 * for (String file : result.getStagedFiles()) {
 *     gitOps.execute("add", file);
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see FileStagingConfig
 * @see StagingResult
 */
public class FileStager implements ConsoleFeatures {

    /**
     * Abstraction over git command execution, allowing callers to provide
     * their own implementation (e.g., wrapping {@link ProcessBuilder}).
     */
    public interface GitOperations {

        /**
         * Executes a git command with the given arguments and returns the
         * process exit code.
         *
         * @param args the git subcommand and its arguments
         *             (e.g., {@code "cat-file", "-e", "origin/master:path"})
         * @return the process exit code (0 for success)
         * @throws IOException if the process cannot be started
         * @throws InterruptedException if the process is interrupted
         */
        int execute(String... args) throws IOException, InterruptedException;

        /**
         * Executes a git command and returns its output, regardless of exit
         * code. Used by {@link TestMethodProtection} to resolve the
         * merge-base commit and to read file content at that commit —
         * information {@link #execute} cannot report because it only
         * returns an exit code.
         *
         * <p>The default implementation always throws. Most guardrails only
         * need an exit code, so most {@code GitOperations} implementations
         * and test fakes never need to override this. A caller that enables
         * {@code protectTestFiles} for Java test sources must supply a real
         * implementation, or every such file will fail closed (see
         * {@link TestMethodProtection}'s fail-safe behavior).</p>
         *
         * @param args the git subcommand and its arguments
         *             (e.g., {@code "show", "abc123:path/to/File.java"})
         * @return the combined standard output and standard error
         * @throws IOException if the process cannot be started
         * @throws InterruptedException if the process is interrupted
         */
        default String executeWithOutput(String... args) throws IOException, InterruptedException {
            throw new UnsupportedOperationException(
                    "executeWithOutput is not implemented by this GitOperations");
        }
    }

    /**
     * Evaluates a list of changed files against the configured guardrails
     * and returns a {@link StagingResult} classifying each file as staged
     * or skipped.
     *
     * <p>This method does NOT perform any git operations. The caller is
     * responsible for staging the files listed in
     * {@link StagingResult#getStagedFiles()}.</p>
     *
     * @param changedFiles     the list of changed file paths (relative to
     *                         the working directory)
     * @param config           the staging configuration with guardrail rules
     * @param workingDirectory the git working directory for resolving file paths
     * @param gitOps           git operations interface for base-branch existence checks
     * @return the staging result with classified file lists
     */
    public StagingResult evaluateFiles(List<String> changedFiles, FileStagingConfig config,
                                       File workingDirectory, GitOperations gitOps) {
        List<String> stagedFiles = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        TestMethodProtection testMethodProtection = new TestMethodProtection();
        String mergeBase = config.isProtectTestFiles()
                ? testMethodProtection.resolveMergeBase(config.getBaseBranch(), gitOps)
                : null;

        for (String file : changedFiles) {
            File f = new File(workingDirectory, file);
            boolean isDeleted = !f.exists();

            // Guardrail 1: Pattern exclusion
            if (matchesAnyPattern(file, config.getExcludedPatterns())) {
                log("Skipping (pattern): " + file);
                skippedFiles.add(file + " (excluded pattern)");
                continue;
            }

            // Guardrail 2: Test file protection
            if (config.isProtectTestFiles()
                    && matchesAnyPattern(file, config.getProtectedPathPatterns())) {
                if (isCiWorkflowFile(file) || !file.endsWith(".java")) {
                    if (existsOnBaseBranch(file, mergeBase, gitOps)) {
                        log("Blocked (protected - exists on " + config.getBaseBranch() + "): " + file);
                        skippedFiles.add(file + " (protected - exists on base branch)");
                        continue;
                    } else {
                        log("Allowed (branch-new file): " + file);
                    }
                } else {
                    TestMethodProtection.Verdict verdict = testMethodProtection.evaluate(
                            file, mergeBase, workingDirectory, gitOps);
                    if (!verdict.isAllowed()) {
                        log("Blocked (" + verdict.getReason() + "): " + file);
                        skippedFiles.add(file + " (protected - " + verdict.getReason() + ")");
                        continue;
                    } else {
                        log("Allowed (" + verdict.getReason() + "): " + file);
                    }
                }
            }

            // Guardrail 3: File size (skip deleted files)
            if (!isDeleted && f.length() > config.getMaxFileSizeBytes()) {
                log("Skipping (size " + formatSize(f.length()) + "): " + file);
                skippedFiles.add(file + " (exceeds " + formatSize(config.getMaxFileSizeBytes()) + ")");
                continue;
            }

            // Guardrail 4: Binary detection (skip deleted files)
            if (!isDeleted && isBinaryFile(f)) {
                log("Skipping (binary): " + file);
                skippedFiles.add(file + " (binary file)");
                continue;
            }

            // File passed all guardrails
            stagedFiles.add(file);
        }

        log("Evaluated " + changedFiles.size() + " files: "
            + stagedFiles.size() + " staged, " + skippedFiles.size() + " skipped");

        return new StagingResult(stagedFiles, skippedFiles);
    }

    /**
     * Checks whether the given path matches any of the provided glob patterns.
     *
     * @param path     the file path to test
     * @param patterns the set of glob patterns to match against
     * @return true if the path matches at least one pattern
     */
    public static boolean matchesAnyPattern(String path, Set<String> patterns) {
        for (String pattern : patterns) {
            if (matchesGlobPattern(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests whether a file path matches a single glob pattern.
     *
     * <p>The glob-to-regex conversion handles {@code **}, {@code *},
     * {@code ?}, and literal dot escaping. In addition to the regex
     * match, the method also checks for suffix and exact equality
     * matches to handle common edge cases.</p>
     *
     * @param path    the file path to test
     * @param pattern the glob pattern
     * @return true if the path matches the pattern
     */
    public static boolean matchesGlobPattern(String path, String pattern) {
        StringBuilder regex = new StringBuilder();
        int i = 0;

        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                // "**/" matches zero or more directories
                if (i + 2 < pattern.length() && pattern.charAt(i + 2) == '/') {
                    regex.append("(.*/)?");
                    i += 3;
                } else {
                    // trailing "**" matches everything
                    regex.append(".*");
                    i += 2;
                }
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if (c == '.') {
                regex.append("\\.");
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }

        String r = regex.toString();
        return Pattern.matches(r, path)
            || Pattern.matches(".*/" + r, path)
            || path.endsWith("/" + pattern)
            || path.equals(pattern);
    }

    /**
     * Determines whether a file appears to be binary by checking for
     * null bytes in its content.
     *
     * <p>Reads up to 8000 bytes from the file. If more than 10% of the
     * examined bytes are null ({@code 0x00}), the file is classified as
     * binary.</p>
     *
     * @param file the file to examine
     * @return true if the file appears to be binary
     */
    public static boolean isBinaryFile(File file) {
        if (!file.exists() || file.isDirectory()) {
            return false;
        }

        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            int checkLength = Math.min(bytes.length, 8000);

            int nullCount = 0;
            for (int j = 0; j < checkLength; j++) {
                if (bytes[j] == 0) {
                    nullCount++;
                    if (nullCount > checkLength / 10) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            // If we can't read it, assume it might be binary
            return true;
        }
    }

    /**
     * Returns whether {@code file} is a GitHub Actions workflow or action
     * definition ({@code .github/workflows/**} or {@code .github/actions/**}).
     *
     * <p>These paths keep whole-file protection rather than the test-method
     * granularity {@link TestMethodProtection} applies to Java test sources:
     * a workflow file has no "test method" structure to reason about, and
     * CI/workflow configuration is locked in full by design (see RULE 3 in
     * {@code validate-agent-commit.sh}).</p>
     *
     * @param file the file path to test
     * @return true if the path is a protected CI/workflow path
     */
    private static boolean isCiWorkflowFile(String file) {
        return matchesGlobPattern(file, ".github/workflows/**")
                || matchesGlobPattern(file, ".github/actions/**");
    }

    /**
     * Formats a byte count into a human-readable string using B, KB, or MB
     * units.
     *
     * @param bytes the number of bytes
     * @return a formatted size string (e.g., "512 B", "1.5 KB", "2.3 MB")
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * Checks whether a file exists at the merge-base by invoking
     * {@code git cat-file -e <mergeBase>:<file>}.
     *
     * <p>Checked against the merge-base rather than the live tip of the base
     * branch, for the same reason {@link TestMethodProtection} is: the base
     * branch keeps moving after a feature branch forks from it, and a
     * tip-based check would misattribute the base branch's own later edits
     * to the agent's branch.</p>
     *
     * <p>Reads {@code cat-file -e}'s exit code as a plain exists/absent
     * boolean: for the {@code <rev>:<path>} form used here, git's revision
     * parsing dies with exit code 128 the moment the path is missing from
     * that tree, not the exit code 1 documented for a raw object id lookup,
     * which this form never produces. Revision parsing cannot distinguish
     * "path absent" from "rev itself unresolvable" by exit code alone, but
     * {@code mergeBase} is only ever the already-validated output of
     * {@link TestMethodProtection#resolveMergeBase}, so the rev is trusted
     * by the time this method runs and any non-zero exit is read as
     * absent.</p>
     *
     * <p>Fails safe: returns {@code true} (protected) if the merge-base is
     * unresolved, or if the check itself errors out — preventing accidental
     * modifications to test files.</p>
     *
     * @param file      the file path to check
     * @param mergeBase the merge-base commit id, or {@code null} if it could
     *                  not be resolved
     * @param gitOps    git operations interface
     * @return true if the file exists at the merge-base
     */
    private boolean existsOnBaseBranch(String file, String mergeBase, GitOperations gitOps) {
        if (mergeBase == null) {
            return true; // Fail safe: protect if merge-base could not be resolved
        }
        try {
            return gitOps.execute("cat-file", "-e", mergeBase + ":" + file) == 0;
        } catch (Exception e) {
            warn("Could not check base branch for " + file + ": " + e.getMessage());
            return true; // Fail safe: protect if uncertain
        }
    }
}
