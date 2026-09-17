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
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link TestHidingAudit#passes}, focused on how each of the
 * audit script's exit codes is interpreted.
 */
public class TestHidingAuditTest extends TestSuiteBase {

    /** Temp directories created during tests; cleaned up by {@link #cleanup}. */
    private final List<Path> tempDirs = new ArrayList<>();

    /**
     * Resets the tracked temp directory list before each test.
     */
    @Before
    public void resetTempDirs() {
        tempDirs.clear();
    }

    /**
     * Deletes all temp directories created during the test.
     */
    @After
    public void cleanup() {
        for (Path dir : tempDirs) {
            deleteRecursively(dir);
        }
    }

    /** Exit code 0 (clean audit) passes. */
    @Test(timeout = 10000)
    public void exitZeroPasses() throws IOException, InterruptedException {
        Path workingDir = installFakeScript("exit 0");
        assertTrue(TestHidingAudit.passes(workingDir.toString(), "master", noopLogger()));
    }

    /** Exit code 2 (violation detected) blocks. */
    @Test(timeout = 10000)
    public void exitTwoBlocks() throws IOException, InterruptedException {
        Path workingDir = installFakeScript("exit 2");
        assertFalse(TestHidingAudit.passes(workingDir.toString(), "master", noopLogger()));
    }

    /**
     * Exit code 1 (an infrastructure failure -- an unresolvable merge-base,
     * an undiffable branch, a modified test file the script could not
     * re-read at the merge-base) must block too, rather than being silently
     * treated the same as a clean exit-0 pass.
     */
    @Test(timeout = 10000)
    public void exitOneBlocks() throws IOException, InterruptedException {
        Path workingDir = installFakeScript("exit 1");
        assertFalse(TestHidingAudit.passes(workingDir.toString(), "master", noopLogger()));
    }

    /** A script entirely absent from a minimal checkout does not block. */
    @Test(timeout = 10000)
    public void missingScriptPasses() throws IOException, InterruptedException {
        Path workingDir = Files.createTempDirectory("test-hiding-audit-test-missing");
        tempDirs.add(workingDir);
        assertTrue(TestHidingAudit.passes(workingDir.toString(), "master", noopLogger()));
    }

    /**
     * Creates a temp working directory with a fake
     * {@code detect-test-hiding.sh} at its expected repo-relative path,
     * whose body is exactly {@code body}.
     *
     * @param body the shell command(s) the fake script should run
     * @return the temp working directory
     * @throws IOException if the script cannot be written
     */
    private Path installFakeScript(String body) throws IOException {
        Path workingDir = Files.createTempDirectory("test-hiding-audit-test");
        tempDirs.add(workingDir);
        Path scriptDir = Files.createDirectories(workingDir.resolve("tools/ci/agent-protection"));
        Files.writeString(scriptDir.resolve("detect-test-hiding.sh"), "#!/usr/bin/env bash\n" + body + "\n");
        return workingDir;
    }

    /**
     * A {@link ConsoleFeatures} sink that discards every message, for tests
     * that do not assert on logging output.
     *
     * @return the discarding logger
     */
    private static ConsoleFeatures noopLogger() {
        return new ConsoleFeatures() { };
    }

    /**
     * Recursively deletes a directory tree; best-effort, swallows errors.
     *
     * @param root the directory (or file) to delete
     */
    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignore) {
                            // best-effort
                        }
                    });
        } catch (Exception ignore) {
            // best-effort
        }
    }
}
