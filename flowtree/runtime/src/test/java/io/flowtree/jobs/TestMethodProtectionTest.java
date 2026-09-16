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
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link TestMethodProtection}: the test-method-granularity
 * guardrail that replaced whole-file blocking for Java test sources.
 *
 * <p>Each test installs the repository's real
 * {@code tools/ci/agent-protection/test-method-lines.awk} script into a temp
 * working directory (rather than a copy), so these tests exercise the exact
 * logic the CI gate uses, not a re-implementation of it.</p>
 */
public class TestMethodProtectionTest extends TestSuiteBase {

    /** A well-formed, arbitrary commit id used as the fake merge-base across tests. */
    private static final String FAKE_MERGE_BASE = "abc1234def5678901234567890abcdef1234567";
    /** The protected test file path used across tests. */
    private static final String FILE = "src/test/java/FooTest.java";

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
     * Creates a temp working directory with the repository's real
     * {@code test-method-lines.awk} installed at its expected repo-relative
     * path.
     *
     * @return the temp working directory
     * @throws IOException if the script cannot be copied
     */
    private Path installAwkScript() throws IOException {
        Path tempDir = Files.createTempDirectory("test-method-protection-test");
        Path scriptDir = Files.createDirectories(tempDir.resolve("tools/ci/agent-protection"));
        Path realScript = locateRealAwkScript();
        Files.copy(realScript, scriptDir.resolve("test-method-lines.awk"), StandardCopyOption.REPLACE_EXISTING);
        return tempDir;
    }

    /**
     * Locates the repository's real {@code test-method-lines.awk}, searching
     * upward from the JVM working directory so the test is independent of
     * whether Maven runs from the module directory or the repo root.
     */
    private Path locateRealAwkScript() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, "tools/ci/agent-protection/test-method-lines.awk");
            if (candidate.isFile()) return candidate.toPath();
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
     * merge-base, a fixed existence result at that merge-base, and fixed
     * {@code git show} content.
     *
     * @param existsAtMergeBase whether {@code cat-file -e} should report the
     *                          file present at the merge-base
     * @param baseContent       the content {@code git show} should return
     * @return the fake git operations
     */
    private static FileStager.GitOperations gitOps(boolean existsAtMergeBase, String baseContent) {
        return new FileStager.GitOperations() {
            @Override
            public int execute(String... args) {
                if (args.length == 3 && "cat-file".equals(args[0]) && "-e".equals(args[1])) {
                    return existsAtMergeBase ? 0 : 1;
                }
                return 1;
            }

            @Override
            public String executeWithOutput(String... args) {
                if (args.length >= 1 && "merge-base".equals(args[0])) return FAKE_MERGE_BASE;
                if (args.length >= 1 && "show".equals(args[0])) return baseContent;
                return "";
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

    /** A file absent from the merge-base is allowed regardless of content. */
    @Test(timeout = 30000)
    public void branchNewFileAllowed() throws IOException {
        Path tempDir = installAwkScript();
        try {
            writeCurrent(tempDir, BASE_ONE_METHOD);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(false, ""));
            assertTrue(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Adding a brand-new {@code @Test} method to a base-branch file is allowed. */
    @Test(timeout = 30000)
    public void addingNewMethodToBaseFileAllowed() throws IOException {
        Path tempDir = installAwkScript();
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
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
            assertTrue(verdict.isAllowed());
            assertTrue(verdict.getReason().contains("testBar"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Editing a test method that was itself absent from the merge-base is allowed. */
    @Test(timeout = 30000)
    public void editingBranchAddedMethodAllowed() throws IOException {
        Path tempDir = installAwkScript();
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
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
            assertTrue(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Modifying the body of a pre-existing test method is blocked. */
    @Test(timeout = 30000)
    public void modifyingPreExistingMethodBlocked() throws IOException {
        Path tempDir = installAwkScript();
        try {
            String current = BASE_ONE_METHOD.replace("assertEquals(1, 1);", "assertEquals(2, 2);");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
            assertFalse(verdict.isAllowed());
            assertTrue(verdict.getReason().contains("testFoo"));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Deleting a pre-existing test method entirely is blocked. */
    @Test(timeout = 30000)
    public void deletingPreExistingMethodBlocked() throws IOException {
        Path tempDir = installAwkScript();
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
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Adding an {@code @Ignore} annotation to a pre-existing test method is blocked. */
    @Test(timeout = 30000)
    public void annotatingPreExistingMethodBlocked() throws IOException {
        Path tempDir = installAwkScript();
        try {
            String current = BASE_ONE_METHOD.replace(
                    "    @Test\n    public void testFoo() {",
                    "    @Test\n    @Ignore\n    public void testFoo() {");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Inserting an early return into a pre-existing test method is blocked, even though the diff is additions only. */
    @Test(timeout = 30000)
    public void earlyReturnInsertedIntoPreExistingMethodBlocked() throws IOException {
        Path tempDir = installAwkScript();
        try {
            String current = BASE_ONE_METHOD.replace(
                    "    public void testFoo() {\n        assertEquals(1, 1);",
                    "    public void testFoo() {\n        if (true) return;\n        assertEquals(1, 1);");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Editing only fixtures/helpers, with every test method untouched, is allowed. */
    @Test(timeout = 30000)
    public void supportOnlyChangesAllowed() throws IOException {
        Path tempDir = installAwkScript();
        try {
            String current = BASE_ONE_METHOD.replace("int value = 1;", "int value = 2;");
            writeCurrent(tempDir, current);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
            assertTrue(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** A file that existed at the merge-base but was deleted on the branch is blocked. */
    @Test(timeout = 30000)
    public void deletedFileThatExistedAtMergeBaseBlocked() throws IOException {
        Path tempDir = installAwkScript();
        try {
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** A null merge-base (unresolvable) fails closed. */
    @Test(timeout = 30000)
    public void nullMergeBaseFailsClosed() throws IOException {
        Path tempDir = installAwkScript();
        try {
            writeCurrent(tempDir, BASE_ONE_METHOD);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, null, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
            assertFalse(verdict.isAllowed());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** A missing shared awk script fails closed rather than silently allowing everything. */
    @Test(timeout = 30000)
    public void missingAwkScriptFailsClosed() throws IOException {
        Path tempDir = Files.createTempDirectory("test-method-protection-test-no-awk");
        try {
            writeCurrent(tempDir, BASE_ONE_METHOD);
            TestMethodProtection.Verdict verdict = new TestMethodProtection().evaluate(
                    FILE, FAKE_MERGE_BASE, tempDir.toFile(), gitOps(true, BASE_ONE_METHOD));
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
        FileStager.GitOperations ops = gitOps(true, BASE_ONE_METHOD);
        String result = new TestMethodProtection().resolveMergeBase("master", ops);
        assertTrue(FAKE_MERGE_BASE.equals(result));
    }
}
