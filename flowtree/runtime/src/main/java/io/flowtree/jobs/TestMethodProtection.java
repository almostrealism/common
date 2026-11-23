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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
     * Matches a {@code @Test} annotation marker, mirroring the awk extractor's
     * {@code @Test([ \t(]|$)} test: the {@code @Test} token must not be followed
     * by an identifier character, so a longer annotation name that merely begins
     * with {@code @Test} does not match.
     */
    private static final Pattern TEST_ANNOTATION = Pattern.compile("@Test(?![A-Za-z0-9_$])");

    /** Matches an identifier immediately followed by an opening parenthesis — a method or annotation call. */
    private static final Pattern IDENTIFIER_CALL = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)[ \t]*\\(");

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

        Map<String, Integer> scanned = annotatedTestMethodCounts(baseContent);
        Map<String, Integer> reported = recordNameCounts(baseMethods);
        Set<String> unaccounted = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : scanned.entrySet()) {
            if (entry.getValue() > reported.getOrDefault(entry.getKey(), 0)) {
                unaccounted.add(entry.getKey());
            }
        }
        if (!unaccounted.isEmpty()) {
            return Verdict.blocked("exists on base branch; the merge-base file has @Test method(s) the shared"
                    + " extractor did not report, so an edit to them cannot be verified: "
                    + String.join(", ", unaccounted));
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
     * Returns the method-name prefix (before the first tab) of one
     * {@code "name\tbody"} record, or the whole record when it has no tab.
     *
     * @param record a {@code "name\tbody"} record
     * @return the method name
     */
    private static String recordName(String record) {
        int tab = record.indexOf('\t');
        return tab >= 0 ? record.substring(0, tab) : record;
    }

    /**
     * Extracts the method-name prefix (before the first tab) from each
     * {@code "name\tbody"} record.
     *
     * @param records the {@code "name\tbody"} records
     * @return the method names, in iteration order
     */
    private static Set<String> recordNames(Set<String> records) {
        Set<String> names = new LinkedHashSet<>();
        for (String record : records) {
            names.add(recordName(record));
        }
        return names;
    }

    /**
     * Counts, per method name, how many {@code "name\tbody"} records the
     * shared extractor reported. The extractor keeps two overloads of the
     * same name as two records, so a name appears more than once here; the
     * cross-check in {@link #evaluate} needs that multiplicity to notice when
     * the extractor dropped one overload but reported another of the same
     * name.
     *
     * @param records the {@code "name\tbody"} records
     * @return a map from method name to the number of records with that name
     */
    private static Map<String, Integer> recordNameCounts(Set<String> records) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String record : records) {
            counts.merge(recordName(record), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Extracts the method-name prefix (before the first tab) from each
     * {@code "name\tbody"} record.
     *
     * @param records the {@code "name\tbody"} records
     * @return the method names, in iteration order
     */
    private static Set<String> recordNames(Set<String> records) {
        Set<String> names = new LinkedHashSet<>();
        for (String record : records) {
            int tab = record.indexOf('\t');
            names.add(tab >= 0 ? record.substring(0, tab) : record);
        }
        return names;
    }

    /**
     * Extracts the method-name prefix (before the first tab) from each
     * record for use in a human-readable skip/allow reason.
     *
     * @param records the {@code "name\tbody"} records
     * @return the method names, comma-separated
     */
    private static String methodNames(Set<String> records) {
        return String.join(", ", recordNames(records));
    }

    /**
     * Independently scans {@code content} for the names of its
     * {@code @Test}-annotated methods, without delegating to the shared awk
     * extractor.
     *
     * <p>This is a defense-in-depth cross-check for {@link #evaluate}: the
     * shared extractor can silently under-report methods (for example, a
     * one-line javadoc containing {@code //} eats its own closing
     * {@code *}{@code /} and swallows the method that follows). When that
     * happens the extractor's view of a protected file is incomplete, and a
     * comparison built on it fails <em>open</em> — exactly what this class
     * promises never to do. Comparing the extractor's reported method names
     * against this scan lets {@link #evaluate} detect the gap and fail
     * closed instead.</p>
     *
     * <p>Unlike the awk extractor, this scan removes comments and string and
     * character literals in source order, so a {@code //} inside a block
     * comment or a string does not terminate anything. It is deliberately
     * conservative: a name it counts more often than the extractor reported
     * it is treated as cause to protect the whole file, which is the safe
     * direction.</p>
     *
     * <p>To stay aligned with the extractor, which only recognizes
     * {@code @Test} at class level (brace depth {@code <= 1}) and ignores
     * methods of nested classes, this scan tracks brace depth and counts a
     * {@code @Test} marker only where the extractor would. It also preserves
     * multiplicity — two overloads of the same name count as two — so that a
     * name the extractor reported once but that occurs twice here is still
     * detected as under-reported.</p>
     *
     * @param content the Java source to scan
     * @return a map from {@code @Test}-annotated method name to the number of
     *         such methods found at class level
     */
    private static Map<String, Integer> annotatedTestMethodCounts(String content) {
        String code = stripCommentsAndLiterals(content);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher annotation = TEST_ANNOTATION.matcher(code);
        int depth = 0;
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                if (depth > 0) depth--;
                i++;
            } else if (c == '@' && depth <= 1 && annotation.region(i, code.length()).lookingAt()) {
                String name = methodNameAfter(code, annotation.end());
                if (name != null) counts.merge(name, 1, Integer::sum);
                i = annotation.end();
            } else {
                i++;
            }
        }
        return counts;
    }

    /**
     * Returns the name of the method whose signature begins after
     * {@code from} in already-stripped {@code code}: the last identifier that
     * is immediately followed by {@code (} in the text up to the method
     * body's opening brace. Parentheses are tracked so a brace inside an
     * annotation argument (such as {@code @ValueSource(ints = {1, 2})}) is
     * not mistaken for the body, and a {@code ;} at top level (an abstract
     * method or a field) yields no name.
     *
     * <p>The naming rule is the shared extractor's rule, not the Java
     * grammar's, and must stay so: the result is compared name-for-name with
     * what the extractor reported. For a signature such as
     * {@code void testFoo(@Size(1) String s)} both report {@code Size}, so the
     * counts agree and the file is not blocked; reporting {@code testFoo} here
     * alone would make every such method look unaccounted and block every
     * edit to its file. The extractor still keys that method's record by
     * {@code Size}, so an edit to its body is still detected.</p>
     *
     * @param code the comment- and literal-stripped source
     * @param from the index just past a {@code @Test} marker
     * @return the method name, or {@code null} if no method body follows
     */
    private static String methodNameAfter(String code, int from) {
        int depth = 0;
        int end = -1;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) depth--;
            } else if (depth == 0 && c == ';') {
                return null;
            } else if (depth == 0 && c == '{') {
                end = i;
                break;
            }
        }
        if (end < 0) return null;

        Matcher call = IDENTIFIER_CALL.matcher(code.substring(from, end));
        String last = null;
        while (call.find()) last = call.group(1);
        return last;
    }

    /**
     * Returns {@code content} with Java comments and string, character and
     * text-block literals replaced by a single space, processing {@code //},
     * {@code /*}, {@code """}, {@code "} and {@code '} in the order they appear
     * so that a delimiter inside a comment or a literal is inert. A backslash
     * escapes the next character inside a literal.
     *
     * <p>Text blocks ({@code """ ... """}) are recognized before ordinary
     * string literals: an unescaped quote or an opening brace inside a text
     * block would otherwise be misread as code and let a later {@code @Test}
     * method go uncounted.</p>
     *
     * @param content the Java source
     * @return the source with comments and literals removed
     */
    private static String stripCommentsAndLiterals(String content) {
        StringBuilder out = new StringBuilder(content.length());
        int n = content.length();
        int i = 0;
        while (i < n) {
            char c = content.charAt(i);
            char next = i + 1 < n ? content.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && content.charAt(i) != '\n') i++;
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < n && !(content.charAt(i) == '*' && i + 1 < n && content.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
                out.append(' ');
            } else if (c == '"' && next == '"' && i + 2 < n && content.charAt(i + 2) == '"') {
                i += 3;
                while (i < n && !(content.charAt(i) == '"' && i + 2 < n
                        && content.charAt(i + 1) == '"' && content.charAt(i + 2) == '"')) {
                    if (content.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 3, n);
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && content.charAt(i) != quote) {
                    if (content.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Shortens a commit id to its first seven characters for display,
     * matching git's default abbreviation length.
     *
     * @param sha the full commit id
     * @return the shortened id
     */
    static String shortSha(String sha) {
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
