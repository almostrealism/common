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

import org.almostrealism.audio.api.Audio;
import org.almostrealism.studio.persistence.LegacyLibraryMigrator;
import org.almostrealism.studio.persistence.LibraryDestination;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tests for {@link LegacyLibraryMigrator}'s legacy-file discovery helpers and
 * its {@link LegacyLibraryMigrator.Result} record. The streaming {@code
 * migrate} path requires a live store-backed {@code AudioLibrary} and is not
 * exercised here; these tests pin the pure file-inspection and result-reporting
 * contract, which is what callers use to decide whether (and what) to migrate.
 *
 * @see LegacyLibraryMigrator
 */
public class LegacyLibraryMigratorTest extends TestSuiteBase {

	/** Root temporary directory for the legacy batch files, removed in teardown. */
	private Path tempDir;

	/** Path prefix for the legacy batch files. */
	private String prefix;

	/**
	 * Creates the temporary directory and the legacy batch-file prefix.
	 *
	 * @throws IOException if directory creation fails
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("legacy-library-migrator-test");
		prefix = tempDir.resolve("lib").toString();
	}

	/**
	 * Recursively deletes the test's temporary directory.
	 *
	 * @throws IOException if directory traversal fails
	 */
	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			}
		}
	}

	/** Writes {@code count} empty legacy batch files at the prefix. */
	private void writeLegacyBatches(int count) {
		LibraryDestination dest = new LibraryDestination(prefix, true);
		for (int i = 0; i < count; i++) {
			dest.save(Audio.AudioLibraryData.newBuilder()
					.addRecordings(Audio.WaveRecording.newBuilder().setKey("r" + i))
					.build());
		}
	}

	/** {@code hasLegacyData} reflects whether any batch file exists at the prefix. */
	@Test(timeout = 60000)
	public void hasLegacyDataReflectsBatchPresence() {
		LibraryDestination empty = new LibraryDestination(prefix, true);
		Assert.assertFalse("An empty destination has no legacy data",
				LegacyLibraryMigrator.hasLegacyData(empty));

		writeLegacyBatches(1);
		Assert.assertTrue("A destination with a batch file has legacy data",
				LegacyLibraryMigrator.hasLegacyData(new LibraryDestination(prefix, true)));
	}

	/** {@code legacyFiles} lists every batch file in index order. */
	@Test(timeout = 60000)
	public void legacyFilesListsEveryBatchInOrder() {
		writeLegacyBatches(3);

		List<String> files = LegacyLibraryMigrator.legacyFiles(new LibraryDestination(prefix, true));
		Assert.assertEquals("All three batch files must be listed", 3, files.size());
		Assert.assertEquals(prefix + "_0.bin", files.get(0));
		Assert.assertEquals(prefix + "_1.bin", files.get(1));
		Assert.assertEquals(prefix + "_2.bin", files.get(2));
	}

	/** {@code totalBytes} sums the sizes of the existing files and ignores missing ones. */
	@Test(timeout = 60000)
	public void totalBytesSumsExistingFilesAndIgnoresMissing() {
		writeLegacyBatches(2);
		List<String> files = LegacyLibraryMigrator.legacyFiles(new LibraryDestination(prefix, true));

		long expected = 0;
		for (String path : files) {
			expected += new File(path).length();
		}
		Assert.assertTrue("The batch files must carry bytes", expected > 0);

		List<String> withMissing = new ArrayList<>(files);
		withMissing.add(prefix + "_absent.bin");
		Assert.assertEquals("A missing file contributes nothing to the total",
				expected, LegacyLibraryMigrator.totalBytes(withMissing));
	}

	/**
	 * The {@link LegacyLibraryMigrator.Result} record exposes its counts,
	 * aliases the submitted count as {@code migratedCount}, and deletes the
	 * legacy files it names.
	 */
	@Test(timeout = 60000)
	public void resultReportsCountsAndDeletesLegacyFiles() {
		writeLegacyBatches(2);
		List<String> files = LegacyLibraryMigrator.legacyFiles(new LibraryDestination(prefix, true));

		LegacyLibraryMigrator.Result result =
				new LegacyLibraryMigrator.Result(5, 2, 1, files, 1234L);

		Assert.assertEquals(5, result.submittedCount());
		Assert.assertEquals("migratedCount aliases submittedCount", 5, result.migratedCount());
		Assert.assertEquals(2, result.skippedCount());
		Assert.assertEquals(1, result.failedCount());
		Assert.assertEquals(1234L, result.totalBytes());
		Assert.assertEquals(files, result.legacyFiles());

		Assert.assertEquals("Both named legacy files must be deleted", 2, result.deleteLegacyFiles());
		Assert.assertFalse("No legacy file may remain after deletion",
				LegacyLibraryMigrator.hasLegacyData(new LibraryDestination(prefix, true)));
	}
}
