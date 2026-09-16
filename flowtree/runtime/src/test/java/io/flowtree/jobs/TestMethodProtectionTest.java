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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link TestMethodProtection}: the test-method-granularity
 * guardrail that replaced whole-file blocking for Java test sources.
 *
 * <p>Each test reads the repository's real
 * {@code tools/ci/agent-protection/test-method-lines.awk} script's content
 * (rather than a copy) and feeds it through the fake
 * {@link FileStager.GitOperations} as the merge-base's version of the
 * script, so these tests exercise the exact logic the CI gate uses, not a
 * re-implementation of it.</p>
 */
public class TestMethodProtectionTest extends TestSuiteBase {

    /** A well-formed, arbitrary commit id used as the fake merge-base across tests. */
    private static final String FAKE_MERGE_BASE = "abc1234def5678901234567890abcdef1234567";
    /** The protected test file path used across tests. */
    private static final String FILE = "src/test/java/FooTest.java";
    /** Path of the shared method-extraction script, relative to the repository root. */
    private static final String AWK_SCRIPT_PATH = "tools/ci/agent-protection/test-method-lines.awk";

    /** Fixture: a base-branch test file with one {@code @Test} method and one helper. */
    private static final String BASE_ONE_METHOD =
            "package com.example;\n\n" +
            "import org.junit.Test;\n\n" +
            "public class FooTest {\n" +
            "    @Test\n" +
            "    public void testFoo() {\n" +
            "        assertEquals(1, 1);\n" +
            "    }\n\n" +
            "    private void helper() {\n" +
            "        int value = 1;\n" +
            "    }\n" +
            "}\n";

    /**
     * Creates a plain temp working directory for a test's current (post-edit)
     * file content, with no awk script installed in it — the fix under test
     * is that {@link TestMethodProtection} no longer reads the extractor
     * from the working tree at all.
     *
     * @return the temp working directory
     * @throws IOException if the directory cannot be created
     */
    private Path tempWorkingDirectory() throws IOException {
        return Files.createTempDirectory("test-method-protection-test");
    }

    /**
     * Reads the repository's real {@code test-method-lines.awk} content,
     * searching upward from the JVM working directory so the test is
     * independent of whether Maven runs from the module directory or the
     * repo root.
     *
     * @return the script's content
     */
    private String realAwkScriptContent() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, "tools/ci/agent-protection/test-method-lines.awk");
            if (candidate.isFile()) {
                try {
                    return Files.readString(candidate.toPath());
                } catch (IOException e) {
                    throw new IllegalStateException("Could not read " + candidate, e);
                }
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("Could not locate tools/ci/agent-protection/test-method-lines.awk"
                + " above " + new File("").getAbsolutePath());
    }

    /**
     * Writes {@code content} to {@link #FILE} inside {@code tempDir},
     * simulating the agent's current (post-edit) working-tree content.
     *
     * @param tempDir the temp working directory
     * @param content the current file content to write
     * @throws IOException if the file cannot be written
     */
    private void writeCurrent(Path tempDir, String content) throws IOException {
        Path filePath = tempDir.resolve(FILE);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);
    }

    /**
     * Builds a fake {@link FileStager.GitOperations} that reports a fixed
     * merge-base and fixed {@code git show} content for {@link #FILE} and
     * the shared awk extractor, both read at that merge-base.
     *
     * @param baseContent      the content {@code git show} should return for
     *                         {@link #FILE} at the merge-base, or
     *                         {@code null} to simulate it being unreadable
     *                         there
     * @param awkScriptContent the content {@code git show} should return for
     *                         the shared awk script at the merge-base, or
     *                         {@code null} to simulate it being unreadable
     *                         there
     * @return the fake git operations
     */
    private static FileStager.GitOperations gitOps(String baseContent, String awkScriptContent) {
        return new FileStager.GitOperations() {
            @Override
            public int execute(String... args) {
                return targetContent(args) != null ? 0 : 1;
            }

            @Override
            public String executeWithOutput(String... args) {
                if (args.length >= 1 && "merge-base".equals(args[0])) return FAKE_MERGE_BASE;
                String content = targetContent(args);
                return content != null ? content : "";
            }

            private String targetContent(String[] args) {
                if (args.length != 2 || !"show".equals(args[0])) return null;
                if ((FAKE_MERGE_BASE + ":" + FILE).equals(args[1])) return baseContent;
                if ((FAKE_MERGE_BASE + ":" + AWK_SCRIPT_PATH).equals(args[1])) return awkScriptContent;
                return null;
            }
        };
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param path the directory (or file) to delete
     * @throws IOException if deletion fails
     */
    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.list(path).forEach(child -> {
                try {
                    deleteRecursively(child);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        Files.deleteIfExists(path);
    }

    /** A file absent from the merge-base is allowed regardless of content, without consulting the awk script at all. */
    @Test(timeout = 30000)
    public void branchNewFileAllowed() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            writeCurrent(tempDir, BASE_ONE_METHOD);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(), tempDir.toFile(), gitOps(null, null));
            assertTrue(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Adding a brand-new {@code @Test} method to a base-branch file is allowed. */
    @Test(timeout = 30000)
    public void addingNewMethodToBaseFileAllowed() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            String current = BASE_ONE_METHOD.replace(
                    "    private void helper()",
                    "    @Test\n"
                    + "    public void testBar() {\n"
                    + "        assertEquals(2, 2);\n"
                    + "    }\n\n"
                    + "    private void helper()");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertTrue(verdict.isAllowed());
            assertTrue(verdict.getReason().contains("testBar"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Editing a test method that was itself absent from the merge-base is allowed. */
    @Test(timeout = 30000)
    public void editingBranchAddedMethodAllowed() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            // "testBar" never existed at the merge-base -- any content the
            // branch has since given it (including edits after its own
            // first addition) reads as a new record, not a modified one.
            String current = BASE_ONE_METHOD.replace(
                    "    private void helper()",
                    "    @Test\n"
                    + "    public void testBar() {\n"
                    + "        assertEquals(3, 3);\n"
                    + "        assertEquals(4, 4);\n"
                    + "    }\n\n"
                    + "    private void helper()");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertTrue(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Modifying the body of a pre-existing test method is blocked. */
    @Test(timeout = 30000)
    public void modifyingPreExistingMethodBlocked() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            String current = BASE_ONE_METHOD.replace("assertEquals(1, 1);", "assertEquals(2, 2);");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertFalse(verdict.isAllowed());
            assertTrue(verdict.getReason().contains("testFoo"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * A working-tree awk script that has been tampered with to always report
     * "no methods" must not defeat the guardrail: the extractor is read from
     * the merge-base, not the working tree, so a modified body is still
     * blocked.
     */
    @Test(timeout = 30000)
    public void tamperedWorkingTreeAwkScriptIsIgnored() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            String current = BASE_ONE_METHOD.replace("assertEquals(1, 1);", "assertEquals(2, 2);");
            writeCurrent(tempDir, current);
            Path tamperedScript = Files.createDirectories(tempDir.resolve("tools/ci/agent-protection"))
                    .resolve("test-method-lines.awk");
            Files.writeString(tamperedScript, "BEGIN { exit 0 }\n");

            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertFalse("The merge-base's real extractor must still catch the modified method, "
                    + "regardless of the working tree's tampered copy", verdict.isAllowed());
            assertTrue(verdict.getReason().contains("testFoo"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Deleting a pre-existing test method entirely is blocked. */
    @Test(timeout = 30000)
    public void deletingPreExistingMethodBlocked() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            String current =
                    "package com.example;\n\nimport org.junit.Test;\n\n"
                    + "public class FooTest {\n"
                    + "    private void helper() {\n"
                    + "        int value = 1;\n"
                    + "    }\n"
                    + "}\n";
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Adding an {@code @Ignore} annotation to a pre-existing test method is blocked. */
    @Test(timeout = 30000)
    public void annotatingPreExistingMethodBlocked() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            String current = BASE_ONE_METHOD.replace(
                    "    @Test\n    public void testFoo() {",
                    "    @Test\n    @Ignore\n    public void testFoo() {");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Inserting an early return into a pre-existing test method is blocked, even though the diff is additions only. */
    @Test(timeout = 30000)
    public void earlyReturnInsertedIntoPreExistingMethodBlocked() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            String current = BASE_ONE_METHOD.replace(
                    "    public void testFoo() {\n        assertEquals(1, 1);",
                    "    public void testFoo() {\n        if (true) return;\n        assertEquals(1, 1);");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Editing only fixtures/helpers, with every test method untouched, is allowed. */
    @Test(timeout = 30000)
    public void supportOnlyChangesAllowed() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            String current = BASE_ONE_METHOD.replace("int value = 1;", "int value = 2;");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertTrue(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** A file that existed at the merge-base but was deleted on the branch is blocked. */
    @Test(timeout = 30000)
    public void deletedFileThatExistedAtMergeBaseBlocked() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** A null merge-base (unresolvable) fails closed. */
    @Test(timeout = 30000)
    public void nullMergeBaseFailsClosed() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            writeCurrent(tempDir, BASE_ONE_METHOD);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, null, Set.of(FILE, AWK_SCRIPT_PATH), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** A null merge-base file listing (unresolvable) fails closed. */
    @Test(timeout = 30000)
    public void nullMergeBaseFilesFailsClosed() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            writeCurrent(tempDir, BASE_ONE_METHOD);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, null, tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, realAwkScriptContent()));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** A shared awk script missing from the merge-base fails closed rather than silently allowing everything. */
    @Test(timeout = 30000)
    public void missingAwkScriptFailsClosed() throws IOException {
        Path tempDir = tempWorkingDirectory();
        try {
            writeCurrent(tempDir, BASE_ONE_METHOD);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, Set.of(FILE), tempDir.toFile(),
                    gitOps(BASE_ONE_METHOD, null));
            assertFalse(verdict.isAllowed());
            assertTrue(verdict.getReason().contains("not found"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** {@link TestMethodProtection#resolveMergeBase} returns null when the git call throws. */
    @Test(timeout = 30000)
    public void resolveMergeBaseFailsClosedOnError() {
        FileStager.GitOperations throwing = new FileStager.GitOperations() {
            @Override
            public int execute(String... args) {
                return 0;
            }

            @Override
            public String executeWithOutput(String... args) throws IOException {
                throw new IOException("network unreachable");
            }
        };
        String result = new TestMethodProtection().resolveMergeBase("master", throwing);
        assertTrue(result == null);
    }

    /** {@link TestMethodProtection#resolveMergeBase} returns null when the output is not a commit id. */
    @Test(timeout = 30000)
    public void resolveMergeBaseFailsClosedOnGarbageOutput() {
        FileStager.GitOperations garbage = new FileStager.GitOperations() {
            @Override
            public int execute(String... args) {
                return 0;
            }

            @Override
            public String executeWithOutput(String... args) {
                return "fatal: not a valid object name";
            }
        };
        String result = new TestMethodProtection().resolveMergeBase("master", garbage);
        assertTrue(result == null);
    }

    /** {@link TestMethodProtection#resolveMergeBase} accepts a well-formed abbreviated or full SHA. */
    @Test(timeout = 30000)
    public void resolveMergeBaseAcceptsValidSha() {
        FileStager.GitOperations ops = gitOps(BASE_ONE_METHOD, realAwkScriptContent());
        String result = new TestMethodProtection().resolveMergeBase("master", ops);
        assertTrue(FAKE_MERGE_BASE.equals(result));
    }

    /** {@link TestMethodProtection#resolveMergeBaseFiles} returns the listed paths on success. */
    @Test(timeout = 30000)
    public void resolveMergeBaseFilesListsTree() {
        FileStager.GitOperations ops = new FileStager.GitOperations() {
            @Override
            public int execute(String... args) {
                return isLsTree(args) ? 0 : 1;
            }

            @Override
            public String executeWithOutput(String... args) {
                return isLsTree(args) ? FILE + "\n" + AWK_SCRIPT_PATH + "\n" : "";
            }

            private boolean isLsTree(String[] args) {
                return args.length == 4 && "ls-tree".equals(args[0]) && FAKE_MERGE_BASE.equals(args[3]);
            }
        };
        Set<String> result = new TestMethodProtection().resolveMergeBaseFiles(FAKE_MERGE_BASE, ops);
        assertTrue(result.contains(FILE));
        assertTrue(result.contains(AWK_SCRIPT_PATH));
    }

    /**
     * {@link TestMethodProtection#resolveMergeBaseFiles} fails closed
     * ({@code null}) when the listing command itself exits non-zero, rather
     * than silently treating every file as absent from the merge-base.
     */
    @Test(timeout = 30000)
    public void resolveMergeBaseFilesFailsClosedOnNonZeroExit() {
        FileStager.GitOperations failing = new FileStager.GitOperations() {
            @Override
            public int execute(String... args) {
                return 128;
            }

            @Override
            public String executeWithOutput(String... args) {
                return "fatal: unable to read tree object";
            }
        };
        Set<String> result = new TestMethodProtection().resolveMergeBaseFiles(FAKE_MERGE_BASE, failing);
        assertNull(result);
    }

    /** {@link TestMethodProtection#resolveMergeBaseFiles} returns null when the git call throws. */
    @Test(timeout = 30000)
    public void resolveMergeBaseFilesFailsClosedOnError() {
        FileStager.GitOperations throwing = new FileStager.GitOperations() {
            @Override
            public int execute(String... args) throws IOException {
                throw new IOException("git executable not found");
            }

            @Override
            public String executeWithOutput(String... args) {
                return "";
            }
        };
        Set<String> result = new TestMethodProtection().resolveMergeBaseFiles(FAKE_MERGE_BASE, throwing);
        assertNull(result);
    }
}
