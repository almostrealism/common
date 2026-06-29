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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link InvalidFileDetector}, which scans a job's working tree for
 * invalid binary ({@code .bin}) litter.
 */
public class InvalidFileDetectorTest extends TestSuiteBase {

	/**
	 * Returns a concrete {@link GitManagedJob} (a {@link CodingAgentJob}) whose
	 * working directory is set, used only to supply that directory to the
	 * detector under test. {@link CodingAgentJob} is the same concrete stand-in
	 * used by {@link GitManagedJobSerializationTest}.
	 */
	private static GitManagedJob jobWithWorkingDir(Path workingDir) {
		CodingAgentJob job = new CodingAgentJob();
		if (workingDir != null) {
			job.setWorkingDirectory(workingDir.toString());
		}
		return job;
	}

	/** Runs a git sub-command in {@code workDir} and waits for it to finish. */
	private static void gitRun(Path workDir, String... args)
			throws IOException, InterruptedException {
		String[] cmd = new String[args.length + 1];
		cmd[0] = GitOperations.resolveGitCommand();
		System.arraycopy(args, 0, cmd, 1, args.length);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(workDir.toFile());
		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		GitOperations.augmentPath(pb);
		int exit = pb.start().waitFor();
		if (exit != 0) throw new IOException("git command failed (exit " + exit + "): " + String.join(" ", cmd));

	/** Recursively deletes a temporary directory tree, ignoring failures. */
	private static void deleteTree(Path root) {
		if (root == null || !Files.exists(root)) return;
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best effort
				}
			});
		} catch (IOException ignored) {
			// best effort
		}
	}

	/** Finds .bin files at every depth while ignoring the .git directory. */
	@Test(timeout = 30000)
	public void detectsBinFilesRecursivelyAndSkipsGitDir() throws IOException {
		Path dir = Files.createTempDirectory("invalid-file-detector");
		try {
			Files.writeString(dir.resolve("model.bin"), "x");
			Files.createDirectories(dir.resolve("sub"));
			Files.writeString(dir.resolve("sub").resolve("weights.bin"), "y");
			Files.writeString(dir.resolve("notes.txt"), "fine");
			// A .bin inside .git must be ignored.
			Files.createDirectories(dir.resolve(".git"));
			Files.writeString(dir.resolve(".git").resolve("index.bin"), "z");

			InvalidFileDetector detector = new InvalidFileDetector(jobWithWorkingDir(dir));
			detector.detect();

			assertTrue("Expected .bin files to be detected", detector.isDetected());
			List<String> found = detector.getInvalidFiles();
			assertEquals("Expected exactly two .bin files (the .git one is skipped)",
					2, found.size());
			List<String> expected = List.of("model.bin",
					"sub" + File.separator + "weights.bin");
			assertTrue("Expected sorted, working-tree-relative paths but was " + found,
					expected.equals(found));
			assertTrue("Description should mention model.bin",
					detector.getDescription().contains("model.bin"));
		} finally {
			deleteTree(dir);
		}
	}

	/** A tree with no .bin files is reported clean. */
	@Test(timeout = 30000)
	public void cleanTreeReportsNoInvalidFiles() throws IOException {
		Path dir = Files.createTempDirectory("invalid-file-detector-clean");
		try {
			Files.writeString(dir.resolve("README.md"), "hello");
			Files.createDirectories(dir.resolve("src"));
			Files.writeString(dir.resolve("src").resolve("Main.java"), "class Main {}");

			InvalidFileDetector detector = new InvalidFileDetector(jobWithWorkingDir(dir));
			detector.detect();

			assertFalse("A clean tree must not be flagged", detector.isDetected());
			assertTrue("No invalid files expected", detector.getInvalidFiles().isEmpty());
			assertTrue("Empty description for a clean tree",
					detector.getDescription().isEmpty());
		} finally {
			deleteTree(dir);
		}
	}

	/** A null working directory does not throw and is treated as clean. */
	@Test(timeout = 30000)
	public void missingWorkingDirectoryIsTreatedAsClean() {
		InvalidFileDetector detector = new InvalidFileDetector(jobWithWorkingDir(null));
		detector.detect();
		assertFalse("A null working directory must not throw or flag", detector.isDetected());
		assertTrue(detector.getInvalidFiles().isEmpty());
	}

	/**
	 * Litter in a dependent repo is detected too, reported with the dependent
	 * repo directory name as a prefix so the offending repo is unambiguous.
	 */
	@Test(timeout = 30000)
	public void detectsBinFilesInDependentRepos() throws IOException {
		Path primary = Files.createTempDirectory("invalid-file-detector-primary");
		Path dependent = Files.createTempDirectory("invalid-file-detector-dep");
		try {
			Files.writeString(primary.resolve("model.bin"), "x");
			Files.writeString(dependent.resolve("weights.bin"), "y");

			InvalidFileDetector detector = new InvalidFileDetector(
					jobWithWorkingDir(primary), List.of(dependent.toString()));
			detector.detect();

			assertTrue("Litter in either repo must be detected", detector.isDetected());
			List<String> found = detector.getInvalidFiles();
			String depPrefixed = dependent.getFileName().toString() + File.separator + "weights.bin";
			assertEquals("Both primary and dependent litter expected", 2, found.size());
			assertTrue("Primary litter reported relative to its root: " + found,
					found.contains("model.bin"));
			assertTrue("Dependent litter reported with repo prefix: " + found,
					found.contains(depPrefixed));
		} finally {
			deleteTree(primary);
			deleteTree(dependent);
		}
	}

	/**
	 * A {@code .bin} file that already exists on the base branch is pre-existing
	 * content, not litter, and must not be flagged — while a newly created
	 * {@code .bin} file in the same tree still is.
	 */
	@Test(timeout = 30000)
	public void binFilesOnBaseBranchAreExempt() throws IOException, InterruptedException {
		Path repo = Files.createTempDirectory("invalid-file-detector-base");
		try {
			gitRun(repo, "init");
			gitRun(repo, "config", "user.email", "test@test.com");
			gitRun(repo, "config", "user.name", "Test");

			// Commit a .bin file, then publish HEAD as origin/master (the base
			// branch) without needing a real remote.
			Files.writeString(repo.resolve("preexisting.bin"), "kept");
			gitRun(repo, "add", "preexisting.bin");
			gitRun(repo, "commit", "-m", "seed");
			gitRun(repo, "update-ref", "refs/remotes/origin/master", "HEAD");

			// New litter the job created -- not on the base branch.
			Files.writeString(repo.resolve("litter.bin"), "junk");

			InvalidFileDetector detector = new InvalidFileDetector(jobWithWorkingDir(repo));
			detector.detect();

			List<String> found = detector.getInvalidFiles();
			assertTrue("New litter must be flagged", detector.isDetected());
			assertTrue("Only the newly created litter is flagged: " + found,
					found.equals(List.of("litter.bin")));
			assertFalse("A .bin file present on the base branch must not be flagged",
					found.contains("preexisting.bin"));
		} finally {
			deleteTree(repo);
		}
	}

	/** Re-running detect() after a cleanup reflects the new clean state. */
	@Test(timeout = 30000)
	public void detectIsRepeatableAfterCleanup() throws IOException {
		Path dir = Files.createTempDirectory("invalid-file-detector-repeat");
		try {
			Path litter = dir.resolve("artifact.bin");
			Files.writeString(litter, "data");

			InvalidFileDetector detector = new InvalidFileDetector(jobWithWorkingDir(dir));
			detector.detect();
			assertTrue("First scan should find the litter", detector.isDetected());

			// Simulate a corrective cleanup, then re-scan with the same detector.
			Files.delete(litter);
			detector.detect();
			assertFalse("Re-scan after cleanup must report clean", detector.isDetected());
		} finally {
			deleteTree(dir);
		}
	}
}
