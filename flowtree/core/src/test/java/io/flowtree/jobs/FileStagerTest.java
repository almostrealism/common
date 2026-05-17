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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link FileStager} covering pattern exclusion, size limits,
 * binary detection, test file protection, deleted file handling, and
 * glob pattern matching.
 */
public class FileStagerTest extends TestSuiteBase {

	@Test(timeout = 30000)
	public void stagesNonExcludedFiles() throws IOException {
		Path tempDir = Files.createTempDirectory("stager-test");
		try {
			Files.writeString(tempDir.resolve("Foo.java"), "public class Foo {}");
			Files.writeString(tempDir.resolve("Bar.java"), "public class Bar {}");

			FileStagingConfig config = FileStagingConfig.builder().build();
			FileStager stager = new FileStager();
			StagingResult result = stager.evaluateFiles(
				Arrays.asList("Foo.java", "Bar.java"),
				config,
				tempDir.toFile(),
				(String... args) -> 0
			);

			assertEquals(2, result.getStagedFiles().size());
			assertTrue(result.getStagedFiles().contains("Foo.java"));
			assertTrue(result.getStagedFiles().contains("Bar.java"));
			assertTrue(result.getSkippedFiles().isEmpty());
		} finally {
			deleteRecursively(tempDir);
		}
	}

	@Test(timeout = 30000)
	public void skipsExcludedPatterns() throws IOException {
		Path tempDir = Files.createTempDirectory("stager-test");
		try {
			Files.writeString(tempDir.resolve(".env"), "SECRET=abc");
			Path targetDir = Files.createDirectories(tempDir.resolve("target"));
			Files.writeString(targetDir.resolve("foo.class"), "bytecode");

			Set<String> excluded = new HashSet<>(Arrays.asList(".env", "target/**"));
			FileStagingConfig config = FileStagingConfig.builder()
				.excludedPatterns(excluded)
				.build();

			FileStager stager = new FileStager();
			StagingResult result = stager.evaluateFiles(
				Arrays.asList(".env", "target/foo.class"),
				config,
				tempDir.toFile(),
				(String... args) -> 0
			);

			assertTrue(result.getStagedFiles().isEmpty());
			assertEquals(2, result.getSkippedFiles().size());
		} finally {
			deleteRecursively(tempDir);
		}
	}

	@Test(timeout = 30000)
	public void skipsOversizedFiles() throws IOException {
		Path tempDir = Files.createTempDirectory("stager-test");
		try {
			byte[] largeContent = new byte[200];
			Arrays.fill(largeContent, (byte) 'A');
			Files.write(tempDir.resolve("big.txt"), largeContent);

			FileStagingConfig config = FileStagingConfig.builder()
				.maxFileSizeBytes(100)
				.build();

			FileStager stager = new FileStager();
			StagingResult result = stager.evaluateFiles(
				Collections.singletonList("big.txt"),
				config,
				tempDir.toFile(),
				(String... args) -> 0
			);

			assertTrue(result.getStagedFiles().isEmpty());
			assertEquals(1, result.getSkippedFiles().size());
			assertTrue(result.getSkippedFiles().get(0).contains("exceeds"));
		} finally {
			deleteRecursively(tempDir);
		}
	}

	@Test(timeout = 30000)
	public void skipsBinaryFiles() throws IOException {
		Path tempDir = Files.createTempDirectory("stager-test");
		try {
			byte[] binaryContent = new byte[100];
			// Fill >10% with null bytes to trigger binary detection
			Arrays.fill(binaryContent, 0, 20, (byte) 0);
			Arrays.fill(binaryContent, 20, 100, (byte) 'A');
			Files.write(tempDir.resolve("image.bin"), binaryContent);

			FileStagingConfig config = FileStagingConfig.builder().build();
			FileStager stager = new FileStager();
			StagingResult result = stager.evaluateFiles(
				Collections.singletonList("image.bin"),
				config,
				tempDir.toFile(),
				(String... args) -> 0
			);

			assertTrue(result.getStagedFiles().isEmpty());
			assertEquals(1, result.getSkippedFiles().size());
			assertTrue(result.getSkippedFiles().get(0).contains("binary"));
		} finally {
			deleteRecursively(tempDir);
		}
	}

	@Test(timeout = 30000)
	public void protectsExistingTestFiles() throws IOException {
		Path tempDir = Files.createTempDirectory("stager-test");
		try {
			Path testDir = Files.createDirectories(tempDir.resolve("src/test/java"));
			Files.writeString(testDir.resolve("FooTest.java"), "public class FooTest {}");

			FileStagingConfig config = FileStagingConfig.builder()
				.protectTestFiles(true)
				.protectedPathPatterns(new HashSet<>(Collections.singletonList("**/src/test/**")))
				.baseBranch("master")
				.build();

			FileStager stager = new FileStager();
			// Git returns exit code 0 meaning file exists on base branch
			StagingResult result = stager.evaluateFiles(
				Collections.singletonList("src/test/java/FooTest.java"),
				config,
				tempDir.toFile(),
				(String... args) -> 0
			);

			assertTrue(result.getStagedFiles().isEmpty());
			assertEquals(1, result.getSkippedFiles().size());
			assertTrue(result.getSkippedFiles().get(0).contains("protected"));
		} finally {
			deleteRecursively(tempDir);
		}
	}

	@Test(timeout = 30000)
	public void allowsBranchNewTestFiles() throws IOException {
		Path tempDir = Files.createTempDirectory("stager-test");
		try {
			Path testDir = Files.createDirectories(tempDir.resolve("src/test/java"));
			Files.writeString(testDir.resolve("NewTest.java"), "public class NewTest {}");

			FileStagingConfig config = FileStagingConfig.builder()
				.protectTestFiles(true)
				.protectedPathPatterns(new HashSet<>(Collections.singletonList("**/src/test/**")))
				.baseBranch("master")
				.build();

			FileStager stager = new FileStager();
			// Git returns exit code 1 meaning file does NOT exist on base branch
			StagingResult result = stager.evaluateFiles(
				Collections.singletonList("src/test/java/NewTest.java"),
				config,
				tempDir.toFile(),
				(String... args) -> 1
			);

			assertEquals(1, result.getStagedFiles().size());
			assertTrue(result.getStagedFiles().contains("src/test/java/NewTest.java"));
			assertTrue(result.getSkippedFiles().isEmpty());
		} finally {
			deleteRecursively(tempDir);
		}
	}

	@Test(timeout = 30000)
	public void handlesDeletedFiles() throws IOException {
		Path tempDir = Files.createTempDirectory("stager-test");
		try {
			// Do NOT create the file on disk -- it represents a deleted file
			FileStagingConfig config = FileStagingConfig.builder().build();
			FileStager stager = new FileStager();
			StagingResult result = stager.evaluateFiles(
				Collections.singletonList("removed/OldClass.java"),
				config,
				tempDir.toFile(),
				(String... args) -> 0
			);

			assertEquals(1, result.getStagedFiles().size());
			assertTrue(result.getStagedFiles().contains("removed/OldClass.java"));
		} finally {
			deleteRecursively(tempDir);
		}
	}

	@Test(timeout = 30000)
	public void globMatchesSingleStar() {
		assertTrue(FileStager.matchesGlobPattern("foo.log", "*.log"));
		// Single star should not cross directory boundaries in the pattern itself,
		// but the implementation also does suffix matching
		assertFalse(FileStager.matchesGlobPattern("foo.txt", "*.log"));
	}

	@Test(timeout = 30000)
	public void globMatchesDoubleStar() {
		assertTrue(FileStager.matchesGlobPattern("target/foo/bar.class", "target/**"));
		assertTrue(FileStager.matchesGlobPattern("target/bar.class", "target/**"));
	}

	@Test(timeout = 30000)
	public void globMatchesExactName() {
		assertTrue(FileStager.matchesGlobPattern(".DS_Store", ".DS_Store"));
		assertFalse(FileStager.matchesGlobPattern("readme.md", ".DS_Store"));
	}

	/**
	 * Recursively deletes a directory and all its contents.
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
}
