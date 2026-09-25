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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a change to a Java test file that already exists on the
 * base branch may be staged, at test-<em>method</em> granularity rather than
 * whole-file granularity.
 *
 * <h2>Why method granularity</h2>
 * <p>{@link FileStager}'s guardrail 2 used to block an entire protected file
 * the moment it existed on the base branch, even when the only change was a
 * brand-new {@code @Test} method. That is what let a real bug through: a job
 * added new tests to an existing base-branch file, fixed the one that was
 * broken, and the whole file — fix included — was silently dropped from
 * staging. This class replaces that whole-file check for {@code .java} test
 * sources with a test-method-level rule: every {@code @Test} method that
 * exists at the merge-base must survive exactly. It applies only to a job
 * submitted with {@code protectTestFiles} — one sent to make failing tests
 * pass, whose agent must not loosen them instead; every branch is otherwise
 * held to {@code test-integrity-check}, which allows an existing test to be
 * edited but not weakened.</p>
 *
 * <h2>Shared implementation</h2>
 * <p>Method extraction is delegated to
 * {@code tools/ci/agent-protection/test-method-lines.awk} — invoked as a subprocess
 * against the script's content <em>at the merge-base</em>, not the working
 * tree's own copy. Reading it from the mutable working tree would let a
 * branch that edits {@code test-method-lines.awk} alongside a modified test
 * method run the comparison against its own altered extractor and defeat
 * the guardrail; the merge-base copy is outside the branch's control.
 * Only {@code @Test}-annotated methods are compared; fixtures, helpers,
 * fields, constructors and nested classes are never locked — see the awk
 * script's header for why.</p>
 *
 * <h2>Merge-base, not base-branch tip</h2>
 * <p>The base-branch revision compared against is the merge-base of
 * {@code origin/<baseBranch>} and {@code HEAD}, not the live tip of the base
 * branch. The base branch keeps moving after a feature branch forks from it;
 * comparing against its current tip would misattribute the base branch's own
 * later edits to the agent's branch.</p>
 *
 * <h2>Fail-safe behavior</h2>
 * <p>Any failure — the merge-base cannot be resolved, its file listing
 * cannot be read, the base revision or current content cannot be read, the
 * shared awk script is missing at the merge-base, or the awk subprocess
 * itself fails — makes the file fail closed: it is treated as protected in
 * full, exactly as it was before this class existed. The verdict's reason
 * always says which step failed.</p>
 */
class TestMethodProtection implements ConsoleFeatures {

    /** Path of the shared method-extraction script, relative to the repository root. */
    private static final String AWK_SCRIPT_PATH = "tools/ci/agent-protection/test-method-lines.awk";

    /** Validates that resolved merge-base output looks like a git object id. */
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-fA-F]{7,40}$");

    /**
     * The outcome of evaluating one file's change against its merge-base
     * content: whether it may be staged, and a short human-readable reason
     * suitable for appending to a {@link StagingResult} skip entry or an
     * "allowed" log line.
     */
    static final class Verdict {
        /** Whether the file may be staged. */
        private final boolean allowed;
        /** Human-readable reason, suitable for a skip entry or an allow log line. */
        private final String reason;

        /**
         * @param allowed whether the file may be staged
         * @param reason  the human-readable reason
         */
        private Verdict(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        /**
         * Creates an allowing verdict.
         *
         * @param reason the human-readable reason the file may be staged
         * @return an allowing verdict
         */
        static Verdict allowed(String reason) {
            return new Verdict(true, reason);
        }

        /**
         * Creates a blocking verdict.
         *
         * @param reason the human-readable reason the file may not be staged
         * @return a blocking verdict
         */
        static Verdict blocked(String reason) {
            return new Verdict(false, reason);
        }

        /**
         * Returns whether the file may be staged.
         *
         * @return whether the file may be staged
         */
        boolean isAllowed() {
            return allowed;
        }

        /**
         * Returns the human-readable reason for this verdict.
         *
         * @return the human-readable reason for this verdict
         */
        String getReason() {
            return reason;
        }
    }

    /**
     * Resolves the merge-base commit of {@code origin/<baseBranch>} and
     * {@code HEAD}, once per {@link FileStager#evaluateFiles} call rather
     * than once per candidate file, since it does not depend on the file
     * being evaluated.
     *
     * @param baseBranch the base branch name (e.g. {@code "master"})
     * @param gitOps     git operations interface
     * @return the resolved merge-base commit id, or {@code null} if it could
     *         not be resolved (any file evaluation will then fail closed)
     */
    String resolveMergeBase(String baseBranch, FileStager.GitOperations gitOps) {
        try {
            String output = gitOps.executeWithOutput("merge-base", "origin/" + baseBranch, "HEAD").trim();
            if (SHA_PATTERN.matcher(output).matches()) {
                return output;
            }
            warn("Could not resolve merge-base with origin/" + baseBranch + ": " + output);
            return null;
        } catch (Exception e) {
            warn("Could not resolve merge-base with origin/" + baseBranch + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the full set of file paths present at {@code mergeBase}, once
     * per {@link FileStager#evaluateFiles} call rather than once per
     * candidate file.
     *
     * <p>Replaces a per-file {@code git cat-file -e <mergeBase>:<file>}
     * existence probe. For the {@code <rev>:<path>} object-name form, git's
     * revision parser reports the identical exit code and message whether a
     * path is genuinely absent from that tree or the lookup itself failed
     * for an unrelated reason (a corrupt object, a transient read error) —
     * the two cases cannot be told apart from the probe's own exit code.
     * Listing the merge-base tree once removes the ambiguity: whether the
     * {@code git ls-tree} invocation itself succeeded is judged exactly
     * once, by {@link FileStager.GitOperations#executeOrNull}, and every
     * later membership check ({@link #evaluate} and
     * {@link FileStager#evaluateFiles}) is then a plain
     * {@link Set#contains}, with no exit-code interpretation left to get
     * wrong.</p>
     *
     * @param mergeBase the merge-base commit id from {@link #resolveMergeBase}
     * @param gitOps    git operations interface
     * @return the set of file paths present at {@code mergeBase}, or
     *         {@code null} if the listing could not be read (any file
     *         evaluation must then fail closed)
     */
    Set<String> resolveMergeBaseFiles(String mergeBase, FileStager.GitOperations gitOps) {
        try {
            String output = gitOps.executeOrNull("ls-tree", "-r", "--name-only", mergeBase);
            if (output == null) {
                warn("Could not list files at merge-base " + shortSha(mergeBase));
                return null;
            }
            Set<String> files = new LinkedHashSet<>();
            for (String line : output.split("\n", -1)) {
                if (!line.isEmpty()) files.add(line);
            }
            return files;
        } catch (Exception e) {
            warn("Could not list files at merge-base " + shortSha(mergeBase) + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Evaluates whether {@code file} may be staged, given its content at
     * {@code mergeBase} and its current content in {@code workingDirectory}.
     *
     * <p>The shared awk extractor is read from {@code mergeBase}, not the
     * working tree: the working tree is the very thing under evaluation, so
     * a branch that edits {@code test-method-lines.awk} alongside a
     * modified test method could otherwise run this comparison against its
     * own altered extractor and defeat the guardrail.</p>
     *
     * @param file             the file path to evaluate, relative to
     *                         {@code workingDirectory}
     * @param mergeBase        the merge-base commit id from
     *                         {@link #resolveMergeBase}, or {@code null}
     * @param mergeBaseFiles   the files present at {@code mergeBase}, from
     *                         {@link #resolveMergeBaseFiles}, or
     *                         {@code null}
     * @param workingDirectory the git working directory
     * @param gitOps           git operations interface
     * @return the verdict for this file
     */
    Verdict evaluate(String file, String mergeBase, Set<String> mergeBaseFiles,
                      File workingDirectory, FileStager.GitOperations gitOps) {
        if (mergeBase == null || mergeBaseFiles == null) {
            return Verdict.blocked("exists on base branch; merge-base could not be resolved");
        }

        if (!mergeBaseFiles.contains(file)) {
            return Verdict.allowed("branch-new file (absent at merge-base " + shortSha(mergeBase) + ")");
        }

        File current = new File(workingDirectory, file);
        if (!current.exists()) {
            return Verdict.blocked("file existed at merge-base " + shortSha(mergeBase) + " and was deleted");
        }

        String awkScriptContent;
        try {
            awkScriptContent = mergeBaseFiles.contains(AWK_SCRIPT_PATH)
                    ? gitOps.executeOrNull("show", mergeBase + ":" + AWK_SCRIPT_PATH)
                    : null;
        } catch (Exception e) {
            return Verdict.blocked("exists on base branch; could not read " + AWK_SCRIPT_PATH
                    + " at merge-base: " + e.getMessage());
        }
        if (awkScriptContent == null) {
            return Verdict.blocked("exists on base branch; " + AWK_SCRIPT_PATH
                    + " not found at merge-base " + shortSha(mergeBase));
        }

        String baseContent;
        try {
            baseContent = gitOps.executeOrNull("show", mergeBase + ":" + file);
        } catch (Exception e) {
            return Verdict.blocked("exists on base branch; could not read merge-base content: " + e.getMessage());
        }
        if (baseContent == null) {
            return Verdict.blocked("exists on base branch; could not read merge-base content for " + file);
        }

        String currentContent;
        try {
            currentContent = Files.readString(current.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Verdict.blocked("exists on base branch; could not read current content: " + e.getMessage());
        }

        Set<String> baseMethods;
        Set<String> currentMethods;
        try {
            baseMethods = extractTestMethodRecords(awkScriptContent, baseContent);
            currentMethods = extractTestMethodRecords(awkScriptContent, currentContent);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Verdict.blocked("exists on base branch; test-method analysis failed: " + e.getMessage());
        }

        Set<String> modifiedOrRemoved = new LinkedHashSet<>(baseMethods);
        modifiedOrRemoved.removeAll(currentMethods);
        if (!modifiedOrRemoved.isEmpty()) {
            return Verdict.blocked("existing test method(s) changed or removed: " + methodNames(modifiedOrRemoved));
        }

        Set<String> added = new LinkedHashSet<>(currentMethods);
        added.removeAll(baseMethods);
        if (!added.isEmpty()) {
            return Verdict.allowed("new test method(s) added: " + methodNames(added));
        }

        return Verdict.allowed("no test method changes (fixtures/helpers/fields only)");
    }

    /**
     * Runs the shared awk script over {@code content} in "methods" mode and
     * returns the resulting records as an unordered set, as a
     * {@code LC_ALL=C sort -u} over its output would: each record is one {@code @Test}
     * method's name and full body (annotations through the closing brace),
     * so any textual change to an existing method — including a pure
     * addition like an inserted early return — makes its record disappear
     * from the set on the other side.
     *
     * @param awkScriptContent the shared {@code test-method-lines.awk}
     *                         script's content, read from the merge-base
     * @param content          the Java source to analyze
     * @return the set of {@code "name\tbody"} records found
     * @throws IOException          if the awk subprocess cannot be started or its
     *                               temporary input files cannot be written
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private Set<String> extractTestMethodRecords(String awkScriptContent, String content)
            throws IOException, InterruptedException {
        File scriptTmp = File.createTempFile("test-method-protection-script-", ".awk");
        File contentTmp = File.createTempFile("test-method-protection-", ".java");
        try {
            Files.writeString(scriptTmp.toPath(), awkScriptContent, StandardCharsets.UTF_8);
            Files.writeString(contentTmp.toPath(), content, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                    "awk", "-f", scriptTmp.getAbsolutePath(), "-v", "mode=methods", contentTmp.getAbsolutePath());
            pb.redirectErrorStream(false);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("awk exited with code " + exitCode);
            }

            Set<String> records = new LinkedHashSet<>();
            for (String line : output.split("\n", -1)) {
                if (!line.isEmpty()) records.add(line);
            }
            return records;
        } finally {
            Files.deleteIfExists(scriptTmp.toPath());
            Files.deleteIfExists(contentTmp.toPath());
        }
    }

    /**
     * Extracts the method-name prefix (before the first tab) from each
     * record for use in a human-readable skip/allow reason.
     *
     * @param records the {@code "name\tbody"} records
     * @return the method names, comma-separated
     */
    private static String methodNames(Set<String> records) {
        StringBuilder names = new StringBuilder();
        for (String record : records) {
            int tab = record.indexOf('\t');
            String name = tab >= 0 ? record.substring(0, tab) : record;
            if (names.length() > 0) names.append(", ");
            names.append(name);
        }
        return names.toString();
    }

    /**
     * Shortens a commit id to its first seven characters for display,
     * matching git's default abbreviation length.
     *
     * @param sha the full commit id
     * @return the shortened id
     */
    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    /**
     * Formats a log message with this class's simple name prefix.
     *
     * @param msg the raw message text
     * @return the formatted log string
     */
    @Override
    public String formatMessage(String msg) {
        return "TestMethodProtection: " + msg;
    }
}
