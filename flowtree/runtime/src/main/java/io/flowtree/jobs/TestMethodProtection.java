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
 * sources with the same test-method-level rule the CI gate already
 * enforces.</p>
 *
 * <h2>Shared implementation</h2>
 * <p>Method extraction is delegated to
 * {@code tools/ci/agent-protection/test-method-lines.awk} — the exact awk
 * script {@code validate-agent-commit.sh} uses — invoked as a subprocess
 * against the working tree's own copy of the script. The harness-side
 * guardrail and the CI-side gate therefore always agree, because they run
 * the identical logic rather than two independent re-implementations of it.
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
 * <p>Any failure — the merge-base cannot be resolved, the base revision or
 * current content cannot be read, the shared awk script is missing from the
 * working tree, or the awk subprocess itself fails — makes the file fail
 * closed: it is treated as protected in full, exactly as it was before this
 * class existed. The verdict's reason always says which step failed.</p>
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
     * Evaluates whether {@code file} may be staged, given its content at
     * {@code mergeBase} and its current content in {@code workingDirectory}.
     *
     * <p>The merge-base existence check reads {@code cat-file -e}'s exit
     * code as a plain exists/absent boolean. For the {@code <rev>:<path>}
     * form used here, git resolves the path through revision parsing, which
     * dies with exit code 128 the moment the path is missing from that
     * tree — not the exit code 1 documented for looking up a raw object id,
     * which this form never produces. Revision parsing cannot distinguish
     * "path absent" from "rev itself unresolvable" by exit code alone, but
     * {@code mergeBase} is always the already-validated output of a prior
     * {@link #resolveMergeBase} call, so the rev is trusted by the time this
     * method runs: any non-zero exit is read as "absent at the merge-base."
     * Only the subprocess itself failing (a thrown exception) fails
     * closed.</p>
     *
     * @param file             the file path to evaluate, relative to
     *                         {@code workingDirectory}
     * @param mergeBase        the merge-base commit id from
     *                         {@link #resolveMergeBase}, or {@code null}
     * @param workingDirectory the git working directory
     * @param gitOps           git operations interface
     * @return the verdict for this file
     */
    Verdict evaluate(String file, String mergeBase, File workingDirectory, FileStager.GitOperations gitOps) {
        if (mergeBase == null) {
            return Verdict.blocked("exists on base branch; merge-base could not be resolved");
        }

        boolean existedAtMergeBase;
        try {
            existedAtMergeBase = gitOps.execute("cat-file", "-e", mergeBase + ":" + file) == 0;
        } catch (Exception e) {
            return Verdict.blocked("exists on base branch; could not check merge-base content: " + e.getMessage());
        }
        if (!existedAtMergeBase) {
            return Verdict.allowed("branch-new file (absent at merge-base " + shortSha(mergeBase) + ")");
        }

        File current = new File(workingDirectory, file);
        if (!current.exists()) {
            return Verdict.blocked("file existed at merge-base " + shortSha(mergeBase) + " and was deleted");
        }

        String baseContent;
        try {
            baseContent = gitOps.executeWithOutput("show", mergeBase + ":" + file);
        } catch (Exception e) {
            return Verdict.blocked("exists on base branch; could not read merge-base content: " + e.getMessage());
        }

        String currentContent;
        try {
            currentContent = Files.readString(current.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Verdict.blocked("exists on base branch; could not read current content: " + e.getMessage());
        }

        File awkScript = new File(workingDirectory, AWK_SCRIPT_PATH);
        if (!awkScript.isFile()) {
            return Verdict.blocked("exists on base branch; " + AWK_SCRIPT_PATH + " not found in working tree");
        }

        Set<String> baseMethods;
        Set<String> currentMethods;
        try {
            baseMethods = extractTestMethodRecords(awkScript, baseContent);
            currentMethods = extractTestMethodRecords(awkScript, currentContent);
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
     * returns the resulting records as an unordered set, mirroring
     * {@code validate-agent-commit.sh}'s {@code test_methods()} /
     * {@code LC_ALL=C sort -u} pipeline: each record is one {@code @Test}
     * method's name and full body (annotations through the closing brace),
     * so any textual change to an existing method — including a pure
     * addition like an inserted early return — makes its record disappear
     * from the set on the other side.
     *
     * @param awkScript the shared {@code test-method-lines.awk} script
     * @param content   the Java source to analyze
     * @return the set of {@code "name\tbody"} records found
     * @throws IOException          if the awk subprocess cannot be started or its
     *                               temporary input file cannot be written
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private Set<String> extractTestMethodRecords(File awkScript, String content)
            throws IOException, InterruptedException {
        File tmp = File.createTempFile("test-method-protection-", ".java");
        try {
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                    "awk", "-f", awkScript.getAbsolutePath(), "-v", "mode=methods", tmp.getAbsolutePath());
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
            Files.deleteIfExists(tmp.toPath());
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
