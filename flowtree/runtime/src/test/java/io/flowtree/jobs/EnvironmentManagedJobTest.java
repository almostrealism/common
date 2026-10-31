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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;

/**
 * Tests for the directory-deletion behaviour of {@link EnvironmentManagedJob},
 * exercised through the concrete {@link CodingAgentJob} subclass. The tree
 * walk that deletes a stale Python virtual environment must tolerate entries
 * that vanish concurrently — the same shared-node race that
 * {@link InvalidFileDetector} guards against — rather than throwing and
 * aborting the environment rebuild.
 */
public class EnvironmentManagedJobTest extends TestSuiteBase {

	/** Number of files written across ten subdirectories in the race test. */
	private static final int RACE_FILE_COUNT = 400;

	/**
	 * Returns the path of the {@code i}th data file under {@code root}, spread
	 * across ten subdirectories. Shared by the fixture that writes the files and
	 * the churn that races the walk deleting them, so both touch the same set.
	 */
	private static Path dataFile(Path root, int i) {
		return root.resolve("sub" + (i % 10)).resolve("file" + i + ".dat");
	}

	/** Writes {@code count} data files under {@code root}. */
	private static void populate(Path root, int count) throws IOException {
		for (int i = 0; i < count; i++) {
			Path file = dataFile(root, i);
			Files.createDirectories(file.getParent());
			Files.writeString(file, "payload");
		}
	}

	/** A populated directory tree is removed in full. */
	@Test(timeout = 30000)
	public void deleteDirectoryRemovesPopulatedTree() throws IOException {
		Path dir = Files.createTempDirectory("environment-managed-job-delete");
		CodingAgentJob job = new CodingAgentJob();
		try {
			populate(dir, 50);
			job.deleteDirectory(dir);
			assertFalse("The whole tree must be deleted", Files.exists(dir));
		} finally {
			job.deleteDirectory(dir);
		}
	}

	/**
	 * Reproduces the shared-node race for the delete path: another actor deletes
	 * files out from under the walk while it runs. A {@link Files#walk} stream
	 * would throw when a listed file is stat'd after it has vanished; the
	 * visitor treats an already-gone entry as success. The walk must therefore
	 * neither throw nor leave the tree behind, no matter who deleted which file.
	 * A throw from {@code deleteDirectory} (the pre-fix behaviour) fails the test.
	 */
	@Test(timeout = 30000)
	public void deleteDirectoryToleratesConcurrentlyVanishingEntries()
			throws IOException, InterruptedException {
		Path dir = Files.createTempDirectory("environment-managed-job-delete-race");
		CodingAgentJob job = new CodingAgentJob();
		AtomicBoolean running = new AtomicBoolean(true);
		Thread churn = null;
		try {
			populate(dir, RACE_FILE_COUNT);

			// Delete only the data files (never directories, never creating new
			// entries) so the sole collision with the walk under test is a file
			// that is already gone when the walk stats it.
			churn = new Thread(() -> {
				while (running.get()) {
					for (int i = 0; i < RACE_FILE_COUNT; i++) {
						try {
							Files.deleteIfExists(dataFile(dir, i));
						} catch (IOException ignored) {
							// racing with the walk under test is the point
						}
					}
				}
			});
			churn.setDaemon(true);
			churn.start();

			// Must not throw despite files vanishing mid-walk.
			job.deleteDirectory(dir);

			running.set(false);
			churn.join(5000);

			assertFalse("The whole tree must be deleted despite the race",
					Files.exists(dir));
		} finally {
			running.set(false);
			if (churn != null) churn.join(5000);
			job.deleteDirectory(dir);
		}
	}
}
