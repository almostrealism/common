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

package org.almostrealism.studio.persistence.test;

import org.almostrealism.studio.persistence.LegacyLibraryMigrator;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Verifies the {@link LegacyLibraryMigrator.Result} view onto leftover legacy
 * batch files: its {@code LibraryCleanup} description is application-neutral, it
 * only offers itself for cleanup once every record migrated without failure (so
 * deleting the legacy files can never discard an un-migrated record), and its
 * removal deletes exactly the files it lists.
 *
 * @see LegacyLibraryMigrator
 */
public class LegacyLibraryMigratorResultTest extends TestSuiteBase {

	/** Temporary directory holding the stand-in legacy batch files. */
	private Path tempDir;

	/**
	 * Creates the temporary directory used to hold the stand-in legacy files.
	 *
	 * @throws IOException if the directory cannot be created
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("legacy-migrator-result-test");
	}

	/**
	 * Deletes the temporary directory and any files left in it.
	 *
	 * @throws IOException if traversal fails
	 */
	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}

	/** Creates a file of the given byte length and returns its absolute path. */
	private String legacyFile(String name, int bytes) throws IOException {
		Path path = tempDir.resolve(name);
		Files.write(path, new byte[bytes]);
		return path.toString();
	}

	/**
	 * The description reports the migrated count and names no downstream
	 * application, so any consumer's cleanup prompt reads correctly.
	 */
	@Test(timeout = 120000)
	public void descriptionIsApplicationNeutral() {
		LegacyLibraryMigrator.Result result =
				new LegacyLibraryMigrator.Result(7, 0, 0, List.of(), 0L);

		Assert.assertTrue("description reports the migrated record count",
				result.description().startsWith("7 records were migrated"));
		Assert.assertFalse("description names no downstream application",
				result.description().contains("Rings"));
	}

	/**
	 * A result with leftover files and no failures is present; a failure (an
	 * un-migrated record whose only copy is a legacy file) withholds the cleanup,
	 * and so does having no leftover bytes at all.
	 */
	@Test(timeout = 120000)
	public void presentOnlyWhenSafeToDelete() {
		Assert.assertTrue("leftover files with no failures are offered for cleanup",
				new LegacyLibraryMigrator.Result(5, 0, 0, List.of("a.bin"), 2048L).isPresent());
		Assert.assertFalse("a failed record withholds the cleanup",
				new LegacyLibraryMigrator.Result(5, 0, 1, List.of("a.bin"), 2048L).isPresent());
		Assert.assertFalse("no leftover bytes means nothing to clean up",
				new LegacyLibraryMigrator.Result(5, 0, 0, List.of(), 0L).isPresent());
	}

	/**
	 * Removal deletes exactly the files the result lists, returns the number
	 * deleted, and tolerates a path that is already gone.
	 *
	 * @throws IOException if the stand-in files cannot be created
	 */
	@Test(timeout = 120000)
	public void removeDeletesListedFiles() throws IOException {
		String present = legacyFile("batch_0.bin", 1024);
		String alsoPresent = legacyFile("batch_1.bin", 512);
		String missing = tempDir.resolve("batch_missing.bin").toString();

		LegacyLibraryMigrator.Result result = new LegacyLibraryMigrator.Result(
				2, 0, 0, List.of(present, alsoPresent, missing), 1536L);

		Assert.assertEquals("only the two existing files are deleted", 2, result.remove());
		Assert.assertFalse("first listed file is gone", new File(present).exists());
		Assert.assertFalse("second listed file is gone", new File(alsoPresent).exists());
	}
}
