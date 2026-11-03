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

import io.flowtree.jobs.agent.AgentRunRequest;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link MavenInstallSpace}: the per-job installed-artifact space
 * that keeps one job's {@code mvn install} output from satisfying another
 * job's build on the same node, and its wiring into the agent environment.
 */
public class MavenInstallSpaceTest extends TestSuiteBase {

    /** The space's directory and prefix are derived from the task id, under the shared root. */
    @Test(timeout = 30000)
    public void prefixAndDirectoryAreDerivedFromTaskId() {
        Path repository = Path.of("/tmp/m2-repo");
        MavenInstallSpace space = new MavenInstallSpace(repository, "5fc3a66a-d22a");

        Assert.assertEquals("flowtree-jobs/5fc3a66a-d22a", space.localPrefix());
        Assert.assertEquals(repository.resolve("flowtree-jobs").resolve("5fc3a66a-d22a"),
                space.getPath());
        Assert.assertEquals("-Daether.enhancedLocalRepository.split=true"
                        + " -Daether.enhancedLocalRepository.localPrefix=flowtree-jobs/5fc3a66a-d22a",
                space.mavenArgs());
    }

    /** A task id cannot escape the shared root or inject further Maven arguments. */
    @Test(timeout = 30000)
    public void unsafeTaskIdCharactersAreReplaced() {
        MavenInstallSpace space = new MavenInstallSpace(Path.of("/tmp/m2-repo"), "../a b/-Dx=y");

        Assert.assertEquals("flowtree-jobs/___a_b_-Dx_y", space.localPrefix());
        Assert.assertEquals(Path.of("/tmp/m2-repo/flowtree-jobs"), space.getPath().getParent());
    }

    /** A space needs a task id to name it. */
    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void emptyTaskIdIsRejected() {
        new MavenInstallSpace(Path.of("/tmp/m2-repo"), "");
    }

    /** Existing MAVEN_ARGS in the launch environment are kept ahead of the space's arguments. */
    @Test(timeout = 30000)
    public void applyToAppendsToExistingMavenArgs() {
        MavenInstallSpace space = new MavenInstallSpace(Path.of("/tmp/m2-repo"), "job-1");
        Map<String, String> env = new HashMap<>();
        env.put(MavenInstallSpace.MAVEN_ARGS, " -B ");

        space.applyTo(env);

        Assert.assertEquals("-B " + space.mavenArgs(), env.get(MavenInstallSpace.MAVEN_ARGS));
    }

    /** Releasing a space deletes its installed artifacts and leaves other jobs' spaces alone. */
    @Test(timeout = 30000)
    public void releaseDeletesOnlyThisJobsSpace() throws IOException {
        Path repository = Files.createTempDirectory("maven-install-space");
        try {
            MavenInstallSpace mine = new MavenInstallSpace(repository, "mine");
            MavenInstallSpace other = new MavenInstallSpace(repository, "other");
            installFakeArtifact(mine.getPath());
            installFakeArtifact(other.getPath());
            Path cached = installFakeArtifact(repository.resolve("cached"));

            mine.release();

            Assert.assertFalse("The released job's space must be deleted", Files.exists(mine.getPath()));
            Assert.assertTrue("Another job's space must survive", Files.exists(other.getPath()));
            Assert.assertTrue("The shared download cache must survive", Files.exists(cached));
        } finally {
            new ManagedDirectory(repository).delete();
        }
    }

    /** Spaces older than the age bound are swept; recent ones and this job's own are kept. */
    @Test(timeout = 30000)
    public void sweepDeletesOnlyExpiredSpacesOfOtherJobs() throws IOException {
        Path repository = Files.createTempDirectory("maven-install-space");
        try {
            Instant now = Instant.now();
            MavenInstallSpace mine = new MavenInstallSpace(repository, "mine");
            Path abandoned = repository.resolve("flowtree-jobs").resolve("abandoned");
            Path running = repository.resolve("flowtree-jobs").resolve("running");
            installFakeArtifact(mine.getPath());
            installFakeArtifact(abandoned);
            installFakeArtifact(running);
            Files.setLastModifiedTime(abandoned, FileTime.from(now.minus(Duration.ofHours(72))));
            Files.setLastModifiedTime(mine.getPath(), FileTime.from(now.minus(Duration.ofHours(72))));

            int swept = mine.sweepExpired(now, Duration.ofHours(48));

            Assert.assertEquals(1, swept);
            Assert.assertFalse("An abandoned space must be swept", Files.exists(abandoned));
            Assert.assertTrue("A recent space must be kept", Files.exists(running));
            Assert.assertTrue("The job's own space is never swept", Files.exists(mine.getPath()));
        } finally {
            new ManagedDirectory(repository).delete();
        }
    }

    /**
     * A positive age bound is honoured; a zero, negative, or non-numeric
     * setting falls back to the default rather than putting the sweep cutoff at
     * or after the current moment, which would delete live jobs' spaces.
     */
    @Test(timeout = 30000)
    public void maxAgeHonoursPositiveAndRejectsNonPositive() {
        String previous = System.getProperty(MavenInstallSpace.MAX_AGE_PROPERTY);
        Duration expectedDefault = Duration.ofHours(MavenInstallSpace.DEFAULT_MAX_AGE_HOURS);
        try {
            System.setProperty(MavenInstallSpace.MAX_AGE_PROPERTY, "12");
            Assert.assertEquals(Duration.ofHours(12), MavenInstallSpace.maxAge());

            System.setProperty(MavenInstallSpace.MAX_AGE_PROPERTY, "0");
            Assert.assertEquals(expectedDefault, MavenInstallSpace.maxAge());

            System.setProperty(MavenInstallSpace.MAX_AGE_PROPERTY, "-5");
            Assert.assertEquals(expectedDefault, MavenInstallSpace.maxAge());

            System.setProperty(MavenInstallSpace.MAX_AGE_PROPERTY, "not-a-number");
            Assert.assertEquals(expectedDefault, MavenInstallSpace.maxAge());
        } finally {
            if (previous == null) {
                System.clearProperty(MavenInstallSpace.MAX_AGE_PROPERTY);
            } else {
                System.setProperty(MavenInstallSpace.MAX_AGE_PROPERTY, previous);
            }
        }
    }

    /**
     * A non-positive {@value MavenInstallSpace#MAX_AGE_PROPERTY} does not cause
     * the sweep to delete other jobs' spaces: the fallback keeps the long
     * default bound, so a recent sibling survives.
     */
    @Test(timeout = 30000)
    public void sweepWithNonPositiveMaxAgePropertyKeepsRecentSpaces() throws IOException {
        Path repository = Files.createTempDirectory("maven-install-space");
        String previous = System.getProperty(MavenInstallSpace.MAX_AGE_PROPERTY);
        System.setProperty(MavenInstallSpace.MAX_AGE_PROPERTY, "0");
        try {
            MavenInstallSpace mine = new MavenInstallSpace(repository, "mine");
            Path sibling = repository.resolve("flowtree-jobs").resolve("sibling");
            installFakeArtifact(mine.getPath());
            installFakeArtifact(sibling);

            int swept = mine.sweepExpired(Instant.now(), MavenInstallSpace.maxAge());

            Assert.assertEquals(0, swept);
            Assert.assertTrue("A recent sibling space must survive a zero max-age setting",
                    Files.exists(sibling));
        } finally {
            if (previous == null) {
                System.clearProperty(MavenInstallSpace.MAX_AGE_PROPERTY);
            } else {
                System.setProperty(MavenInstallSpace.MAX_AGE_PROPERTY, previous);
            }
            new ManagedDirectory(repository).delete();
        }
    }

    /**
     * A prepared job launches its agent with MAVEN_ARGS naming its own space,
     * and releasing the job's environment deletes that space.
     */
    @Test(timeout = 120000)
    public void preparedJobLaunchesAgentInItsOwnSpace() throws Exception {
        Path repository = Files.createTempDirectory("maven-install-space");
        String previous = System.getProperty(MavenInstallSpace.LOCAL_REPOSITORY_PROPERTY);
        System.setProperty(MavenInstallSpace.LOCAL_REPOSITORY_PROPERTY, repository.toString());
        try {
            CodingAgentJob job = new CodingAgentJob("task-42", "do work");
            Map<String, String> unprepared = new HashMap<>();
            job.applyEnvironmentTo(unprepared);
            Assert.assertFalse("An unprepared job has no install space to apply",
                    unprepared.containsKey(MavenInstallSpace.MAVEN_ARGS));

            job.prepareEnvironment();
            MavenInstallSpace space = new MavenInstallSpace(repository, "task-42");
            Assert.assertEquals(repository.resolve("flowtree-jobs").resolve("task-42"), space.getPath());

            AgentRunRequest req = job.buildRunRequest(
                    "Read,Edit", "{\"mcpServers\":{}}", Path.of("/tmp/x.json"), 0);
            String mavenArgs = req.getEnvironment().get(MavenInstallSpace.MAVEN_ARGS);
            Assert.assertNotNull("The agent must be launched with MAVEN_ARGS", mavenArgs);
            Assert.assertTrue("MAVEN_ARGS must name the job's space: " + mavenArgs,
                    mavenArgs.endsWith(space.mavenArgs()));

            installFakeArtifact(space.getPath());
            job.releaseEnvironment();
            Assert.assertFalse("Releasing the job must delete its space", Files.exists(space.getPath()));
        } finally {
            if (previous == null) {
                System.clearProperty(MavenInstallSpace.LOCAL_REPOSITORY_PROPERTY);
            } else {
                System.setProperty(MavenInstallSpace.LOCAL_REPOSITORY_PROPERTY, previous);
            }
            new ManagedDirectory(repository).delete();
        }
    }

    /**
     * Writes a jar where Maven would install {@code org.example:demo:1.0}
     * under {@code root}.
     *
     * @return the jar's path
     */
    private static Path installFakeArtifact(Path root) throws IOException {
        Path jar = root.resolve("org/example/demo/1.0/demo-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        return jar;
    }
}
