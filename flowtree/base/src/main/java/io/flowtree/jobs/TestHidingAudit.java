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
import java.nio.file.Path;

/**
 * Runs the {@code tools/ci/agent-protection/detect-test-hiding.sh} integrity
 * gate against the base branch before an agent's changes are committed.
 *
 * <p>The script inspects the branch diff for the documented test-hiding
 * deception patterns (TestDepth escalation, tolerance weakening, dimension
 * reduction, net assertion loss, and so on). An exit code of {@code 2} means
 * a violation was detected and the commit must be blocked; any other exit code
 * (including the script being absent on minimal checkouts) is treated as
 * "no violation found" so the gate never blocks a job for infrastructural
 * reasons.</p>
 *
 * <p>A dependency-free, runtime-agnostic helper: it needs only a working
 * directory, a base branch, and a {@link ConsoleFeatures} sink, so it lives in
 * {@code flowtree/base} alongside {@link GitOperations} (whose
 * {@code augmentPath} it reuses) rather than in any orchestrating module. The
 * coding-agent orchestrator in {@code flowtree/runtime} consults it as a
 * self-contained pre-completion gate but does not need to understand it
 * line-by-line.</p>
 *
 * @author Michael Murray
 */
public final class TestHidingAudit {

    /** Location of the audit script relative to the repository root. */
    public static final String SCRIPT_SUBPATH =
            "tools/ci/agent-protection/detect-test-hiding.sh";

    /** Exit code the script uses to signal that a test-hiding violation was found. */
    private static final int VIOLATION_EXIT_CODE = 2;

    /** Static-only audit entry point; instances are not meaningful. */
    private TestHidingAudit() {
    }

    /**
     * Runs the test-hiding audit script and reports whether the changes pass.
     *
     * @param workingDirectory the agent's working directory (repository root),
     *                         or {@code null} to resolve the script relative to
     *                         the current process directory
     * @param baseBranch       the base branch to diff against; {@code null}
     *                         defaults to {@code "master"}
     * @param logger           sink for {@code log}/{@code warn} diagnostics
     * @return {@code false} only when the script reports a violation
     *         (exit code {@value #VIOLATION_EXIT_CODE}); {@code true} otherwise
     * @throws IOException          if the audit process cannot be started
     * @throws InterruptedException if the current thread is interrupted while
     *                              waiting for the audit process to exit
     */
    public static boolean passes(String workingDirectory, String baseBranch,
                                 ConsoleFeatures logger)
            throws IOException, InterruptedException {
        Path auditScript = workingDirectory != null
                ? Path.of(workingDirectory, SCRIPT_SUBPATH)
                : Path.of(SCRIPT_SUBPATH);
        if (!Files.exists(auditScript)) {
            logger.log("detect-test-hiding.sh not found, skipping validation");
            return true;
        }

        String base = baseBranch != null ? baseBranch : "master";
        ProcessBuilder pb = new ProcessBuilder("bash", auditScript.toString(),
                "origin/" + base);
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);
        Process p = pb.start();
        String auditOutput = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();

        if (code == VIOLATION_EXIT_CODE) {
            logger.warn("Test-hiding violations detected - aborting commit:\n" + auditOutput);
            return false;
        } else if (code != 0) {
            logger.warn("detect-test-hiding.sh exited with code " + code + ": " + auditOutput);
        }

        logger.log("Test integrity check passed");
        return true;
    }
}
