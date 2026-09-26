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
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GitOperations#hasUncommittedChanges(String)}.
 *
 * <p>These tests verify that the harness's "no code changes" detector correctly
 * inspects individual repository roots, including dependent repositories, so that
 * agents whose only changes land in a dependent repo are not falsely flagged as
 * having produced no output.</p>
 */
public class GitOperationsTest extends TestSuiteBase {

    /** Temp directories created during tests; cleaned up by {@link #cleanup}. */
    private final List<Path> tempDirs = new ArrayList<>();

    /**
     * Creates a temp directory tracked for cleanup.
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

    /**
     * Tests that a clean committed repository reports no uncommitted changes.
     */
    @Test(timeout = 10000)
    public void returnsFalseOnCleanRepo() throws Exception {
        Path repo = initRepo();
        Path file = repo.resolve("hello.txt");
        Files.writeString(file, "initial");
        gitRun(repo, "add", "hello.txt");
        gitRun(repo, "commit", "-m", "init");

        assertFalse("Clean committed repo must report no uncommitted changes",
                GitOperations.hasUncommittedChanges(repo.toString()));
    }

    /**
     * Tests that an untracked file is detected as an uncommitted change.
     */
    @Test(timeout = 10000)
    public void returnsTrueForUntrackedFile() throws Exception {
        Path repo = initRepo();
        // Commit something so the repo is valid, then add an untracked file.
        Path seed = repo.resolve("seed.txt");
        Files.writeString(seed, "seed");
        gitRun(repo, "add", "seed.txt");
        gitRun(repo, "commit", "-m", "seed");

        Path untracked = repo.resolve("Work.java");
        Files.writeString(untracked, "class Work {}");

        assertTrue("Untracked file must be detected as uncommitted change",
                GitOperations.hasUncommittedChanges(repo.toString()));
    }

    /**
     * Tests that a staged but uncommitted change is detected.
     */
    @Test(timeout = 10000)
    public void returnsTrueForStagedUncommittedFile() throws Exception {
        Path repo = initRepo();
        Path file = repo.resolve("staged.txt");
        Files.writeString(file, "v1");
        gitRun(repo, "add", "staged.txt");
        gitRun(repo, "commit", "-m", "v1");

        // Modify and stage but don't commit.
        Files.writeString(file, "v2");
        gitRun(repo, "add", "staged.txt");

        assertTrue("Staged but uncommitted change must be detected",
                GitOperations.hasUncommittedChanges(repo.toString()));
    }

    /**
     * Tests that a modified tracked file is detected as an uncommitted change.
     */
    @Test(timeout = 10000)
    public void returnsTrueForModifiedTrackedFile() throws Exception {
        Path repo = initRepo();
        Path file = repo.resolve("modified.txt");
        Files.writeString(file, "original");
        gitRun(repo, "add", "modified.txt");
        gitRun(repo, "commit", "-m", "original");

        Files.writeString(file, "modified");

        assertTrue("Modified tracked file must be detected as uncommitted change",
                GitOperations.hasUncommittedChanges(repo.toString()));
    }

    /**
     * Tests that files in excluded paths do not trigger uncommitted-changes detection.
     */
    @Test(timeout = 10000)
    public void ignoredExcludedPathsDoNotTrigger() throws Exception {
        Path repo = initRepo();
        // Commit an initial file to establish a valid HEAD.
        Path seed = repo.resolve("seed.txt");
        Files.writeString(seed, "seed");
        gitRun(repo, "add", "seed.txt");
        gitRun(repo, "commit", "-m", "seed");

        // Create files in paths that GitOperations.isExcludedPath() ignores.
        Files.createDirectories(repo.resolve("target"));
        Files.writeString(repo.resolve("target/Foo.class"), "bytecode");
        Files.createDirectories(repo.resolve(".claude"));
        Files.writeString(repo.resolve(".claude/settings.json"), "{}");

        assertFalse("Files in excluded paths must not trigger uncommitted-changes detection",
                GitOperations.hasUncommittedChanges(repo.toString()));
    }

    /**
     * Tests that changes in dependent repositories are detected separately.
     */
    @Test(timeout = 10000)
    public void dependentRepoChangesDetectedSeparately() throws Exception {
        Path primaryRepo = initRepo();
        Path depRepo = initRepo();

        // Primary repo: clean committed state.
        Path primaryFile = primaryRepo.resolve("Primary.java");
        Files.writeString(primaryFile, "class Primary {}");
        gitRun(primaryRepo, "add", "Primary.java");
        gitRun(primaryRepo, "commit", "-m", "primary init");

        // Dependent repo: seed commit, then a new untracked file.
        Path depSeed = depRepo.resolve("seed.txt");
        Files.writeString(depSeed, "seed");
        gitRun(depRepo, "add", "seed.txt");
        gitRun(depRepo, "commit", "-m", "dep seed");
        Files.writeString(depRepo.resolve("New.java"), "class New {}");

        assertFalse("Primary repo must be clean",
                GitOperations.hasUncommittedChanges(primaryRepo.toString()));
        assertTrue("Dependent repo must report its own uncommitted change",
                GitOperations.hasUncommittedChanges(depRepo.toString()));
    }

    /**
     * Tests that {@link GitOperations#getChangedFiles} lists modified,
     * untracked, and excluded-path files alike, unfiltered — the raw
     * material {@code hasUncommittedChanges} and staging previews both
     * build on.
     */
    @Test(timeout = 10000)
    public void getChangedFilesListsEveryChangedPathUnfiltered() throws Exception {
        Path repo = initRepo();
        Path tracked = repo.resolve("tracked.txt");
        Files.writeString(tracked, "v1");
        gitRun(repo, "add", "tracked.txt");
        gitRun(repo, "commit", "-m", "seed");

        Files.writeString(tracked, "v2");
        Files.writeString(repo.resolve("Untracked.java"), "class Untracked {}");
        Files.createDirectories(repo.resolve("target"));
        Files.writeString(repo.resolve("target/Foo.class"), "bytecode");

        List<String> changed = GitOperations.getChangedFiles(repo.toString());
        assertTrue("Modified tracked file must be listed", changed.contains("tracked.txt"));
        assertTrue("Untracked file must be listed", changed.contains("Untracked.java"));
        assertTrue("Excluded-path file must still be listed (unfiltered)",
                changed.contains("target/Foo.class"));
    }

    /** A clean repository reports no changed files. */
    @Test(timeout = 10000)
    public void getChangedFilesEmptyOnCleanRepo() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("seed.txt"), "seed");
        gitRun(repo, "add", "seed.txt");
        gitRun(repo, "commit", "-m", "seed");

        assertTrue("Clean repo must report no changed files",
                GitOperations.getChangedFiles(repo.toString()).isEmpty());
    }

    /**
     * Tests that {@link GitOperations#listFilesOnRef} returns the files tracked
     * on a ref, filtered by suffix, and ignores untracked working-tree files.
     */
    @Test(timeout = 10000)
    public void listFilesOnRefFiltersBySuffix() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("model.bin"), "x");
        Files.createDirectories(repo.resolve("sub"));
        Files.writeString(repo.resolve("sub/weights.bin"), "y");
        Files.writeString(repo.resolve("README.md"), "docs");
        gitRun(repo, "add", "model.bin", "sub/weights.bin", "README.md");
        gitRun(repo, "commit", "-m", "seed");
        // Publish HEAD as the base-branch remote-tracking ref without a remote.
        gitRun(repo, "update-ref", "refs/remotes/origin/master", "HEAD");

        // An untracked .bin must NOT appear -- only what is tracked on the ref.
        Files.writeString(repo.resolve("untracked.bin"), "z");

        Set<String> binFiles = GitOperations.listFilesOnRef(
                repo.toString(), "origin/master", ".bin", warn());
        Assert.assertEquals("Expected exactly the two committed .bin files",
                Set.of("model.bin", "sub/weights.bin"), binFiles);

        Set<String> allFiles = GitOperations.listFilesOnRef(
                repo.toString(), "origin/master", null, warn());
        assertTrue("Null suffix returns every tracked file",
                allFiles.containsAll(Set.of("model.bin", "sub/weights.bin", "README.md")));
    }

    /**
     * Tests that {@link GitOperations#listFilesOnRef} fails safe with an empty
     * set when the ref cannot be resolved.
     */
    @Test(timeout = 10000)
    public void listFilesOnRefReturnsEmptyForUnknownRef() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("seed.txt"), "seed");
        gitRun(repo, "add", "seed.txt");
        gitRun(repo, "commit", "-m", "seed");

        Set<String> files = GitOperations.listFilesOnRef(
                repo.toString(), "origin/does-not-exist", ".bin", warn());
        assertTrue("Unknown ref must yield an empty set", files.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A warning consumer that discards messages, for tests that do not assert on them. */
    private static Consumer<String> warn() {
        return message -> { /* ignored in tests */ };
    }

    /**
     * Initialises a new git repository in a temp directory and returns the path.
     */
    private Path initRepo() throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("git-ops-test-");
        tempDirs.add(dir);
        gitRun(dir, "init");
        gitRun(dir, "config", "user.email", "test@test.com");
        gitRun(dir, "config", "user.name", "Test");
        return dir;
    }

    /**
     * Runs a git sub-command in {@code workDir} and waits for it to finish.
     */
    private static void gitRun(Path workDir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = GitOperations.resolveGitCommand();
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);
        pb.start().waitFor();
    }

    /**
     * Recursively deletes a directory tree; best-effort, swallows errors.
     */
    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignore) { /* best-effort */ }
                    });
        } catch (Exception ignore) {
            // best-effort
        }
    }

    /**
     * The same repository written as an SSH remote, an HTTPS clone URL, and
     * the suffix-less browser URL a CI system reports must reduce to one
     * slug — this is what keeps a submission from creating a duplicate
     * workstream for a repository that already has one.
     */
    @Test(timeout = 10000)
    public void repositorySlugNormalizesUrlForms() {
        Assert.assertEquals("almostrealism/common",
                GitOperations.repositorySlug("git@github.com:almostrealism/common.git"));
        Assert.assertEquals("almostrealism/common",
                GitOperations.repositorySlug("https://github.com/almostrealism/common.git"));
        Assert.assertEquals("almostrealism/common",
                GitOperations.repositorySlug("https://github.com/almostrealism/common"));
        Assert.assertEquals("almostrealism/common",
                GitOperations.repositorySlug("ssh://git@github.com/almostrealism/common.git"));
        Assert.assertEquals("almostrealism/common",
                GitOperations.repositorySlug("  https://github.com/almostrealism/common/  "));
    }

    /** A URL that is not a repository URL has no slug. */
    @Test(timeout = 10000)
    public void repositorySlugRejectsUnrecognisedInput() {
        Assert.assertNull(GitOperations.repositorySlug(null));
        Assert.assertNull(GitOperations.repositorySlug(""));
        Assert.assertNull(GitOperations.repositorySlug("   "));
        Assert.assertNull(GitOperations.repositorySlug("not-a-url"));
        Assert.assertNull(GitOperations.repositorySlug("https://github.com/almostrealism"));
    }

    /**
     * The host is reported for every repository URL form {@code repositorySlug}
     * recognises, so that a caller can reject a URL pointing at the wrong
     * service. Credentials and a trailing slash are tolerated, and the {@code @}
     * of an SSH remote does not leak into the host.
     */
    @Test(timeout = 10000)
    public void repositoryHostExtractsHostForRecognisedForms() {
        Assert.assertEquals("github.com",
                GitOperations.repositoryHost("git@github.com:almostrealism/common.git"));
        Assert.assertEquals("github.com",
                GitOperations.repositoryHost("https://github.com/almostrealism/common.git"));
        Assert.assertEquals("github.com",
                GitOperations.repositoryHost("https://github.com/almostrealism/common"));
        Assert.assertEquals("github.com",
                GitOperations.repositoryHost("ssh://git@github.com/almostrealism/common.git"));
        Assert.assertEquals("github.com",
                GitOperations.repositoryHost("  https://github.com/almostrealism/common/  "));
        Assert.assertEquals("github.com",
                GitOperations.repositoryHost("https://x-access-token:secret@github.com/almostrealism/common.git"));
        Assert.assertEquals("gitlab.com",
                GitOperations.repositoryHost("https://gitlab.com/acme/repo.git"));
    }

    /**
     * An explicit port is excluded from the reported host, so a URL with a port
     * reduces to the same host as one without — a look-alike host such as
     * {@code github.com.evil.example} is reported verbatim and stays distinct.
     */
    @Test(timeout = 10000)
    public void repositoryHostExcludesPort() {
        Assert.assertEquals("github.com",
                GitOperations.repositoryHost("https://github.com:443/almostrealism/common.git"));
        Assert.assertEquals("github.com",
                GitOperations.repositoryHost("ssh://git@github.com:22/almostrealism/common.git"));
        Assert.assertEquals("github.com.evil.example",
                GitOperations.repositoryHost("https://github.com.evil.example/almostrealism/common.git"));
    }

    /** A URL that is not a repository URL has no host. */
    @Test(timeout = 10000)
    public void repositoryHostRejectsUnrecognisedInput() {
        Assert.assertNull(GitOperations.repositoryHost(null));
        Assert.assertNull(GitOperations.repositoryHost(""));
        Assert.assertNull(GitOperations.repositoryHost("   "));
        Assert.assertNull(GitOperations.repositoryHost("not-a-url"));
        Assert.assertNull(GitOperations.repositoryHost("https://github.com/almostrealism"));
    }

    /** Equivalent forms of one repository are the same repository. */
    @Test(timeout = 10000)
    public void sameRepositoryAcrossUrlForms() {
        assertTrue(GitOperations.isSameRepository(
                "git@github.com:almostrealism/common.git",
                "https://github.com/almostrealism/common"));
        assertTrue(GitOperations.isSameRepository(
                "https://github.com/AlmostRealism/Common.git",
                "git@github.com:almostrealism/common"));
    }

    /** Different repositories, absent URLs, and unparseable URLs do not match. */
    @Test(timeout = 10000)
    public void differentRepositoriesDoNotMatch() {
        assertFalse(GitOperations.isSameRepository(
                "git@github.com:almostrealism/common.git",
                "git@github.com:almostrealism/other.git"));
        assertFalse(GitOperations.isSameRepository(
                null, "git@github.com:almostrealism/common.git"));
        assertFalse(GitOperations.isSameRepository(
                "git@github.com:almostrealism/common.git", null));
        assertFalse(GitOperations.isSameRepository("not-a-url", "also-not-a-url"));
        assertTrue("An unparseable URL still matches itself",
                GitOperations.isSameRepository("not-a-url", "not-a-url"));
    }
}
