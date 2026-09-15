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

import io.flowtree.node.AutomaticLabel;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that a job can say where it landed — the machine's automatic
 * labels and the branch head it checked out — since the controller never
 * learns which worker took a job.
 */
public class JobPlacementTest extends TestSuiteBase {

    /** Runs a git sub-command in {@code workDir}, failing the test when it fails. */
    private static void git(Path workDir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = GitOperations.resolveGitCommand();
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        GitOperations.augmentPath(pb);
        Assert.assertEquals("git " + String.join(" ", args), 0, pb.start().waitFor());
    }

    /** The machine description is in {@code key=value} terms and always knows its platform. */
    @Test(timeout = 30000)
    public void machineIsDescribedInLabelTerms() {
        String described = AutomaticLabel.describeMachine();
        assertTrue(described, described.contains(AutomaticLabel.PLATFORM.key() + "="));
        assertFalse(described, described.startsWith(","));
    }

    /** With a checked-out repository, the placement names the target branch and its short head. */
    @Test(timeout = 60000)
    public void placementNamesTheBranchHead() throws IOException, InterruptedException {
        Path repo = Files.createTempDirectory("job-placement");
        git(repo, "init", "-q");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.com",
                "commit", "-q", "--allow-empty", "-m", "initial");

        CodingAgentJob job = new CodingAgentJob("j1", "do the thing");
        job.setWorkingDirectory(repo.toString());
        job.setTargetBranch("feature/x");

        String head = job.executeGitWithOutput("rev-parse", "--short", "HEAD").trim();
        String placement = job.describePlacement();
        assertTrue(placement, placement.endsWith(" at feature/x@" + head));
        assertTrue(placement, placement.contains(AutomaticLabel.describeMachine()));
    }

    /** Without a repository the placement is still the machine description, never a failure. */
    @Test(timeout = 30000)
    public void placementWithoutARepositoryIsJustTheMachine() throws IOException {
        CodingAgentJob job = new CodingAgentJob("j1", "do the thing");
        job.setWorkingDirectory(Files.createTempDirectory("job-placement-empty").toString());
        assertEquals(AutomaticLabel.describeMachine(), job.describePlacement());
    }
}
