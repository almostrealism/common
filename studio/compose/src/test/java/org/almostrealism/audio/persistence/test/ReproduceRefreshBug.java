/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio.persistence.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.persistence.AudioLibraryPersistence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Reproduces the bug where {@link AudioLibrary#refresh()} recomputes
 * features for every entry despite the protobuf already containing
 * complete feature data.
 *
 * <p>The root cause is that {@code refresh()} calls
 * {@code getOrLoad(id)} which requires the full {@link org.almostrealism.audio.data.WaveDetails}
 * to be in cache or loadable from disk. When entries have been evicted
 * from the {@code FrequencyCache}, the completeness check fails or
 * triggers expensive re-loading, and jobs are submitted unnecessarily.</p>
 */
public class ReproduceRefreshBug extends TestSuiteBase {

	private static final String PROTOBUF_PREFIX = "/Users/michael/Projects/AlmostRealism/library";
	private static final String SAMPLES_ROOT = "/Users/michael/Music/Samples";
	private static final int SAMPLE_RATE = 44100;

	@Test
	public void refreshDoesNotRecomputeCompleteEntries() throws Exception {
		File samplesDir = new File(SAMPLES_ROOT);
		if (!samplesDir.isDirectory()) {
			System.out.println("SKIP: Samples directory not found: " + SAMPLES_ROOT);
			return;
		}

		File protobufFile = new File(PROTOBUF_PREFIX + "_0.bin");
		if (!protobufFile.exists()) {
			System.out.println("SKIP: Protobuf file not found: " + protobufFile);
			return;
		}

		// Step 1: Create library pointed at real samples directory
		AudioLibrary library = new AudioLibrary(samplesDir, SAMPLE_RATE);

		try {
			// Step 2: Load from protobuf (the standard app startup path)
			AudioLibraryPersistence.loadLibrary(library, PROTOBUF_PREFIX);
			int identifierCount = library.getAllIdentifiers().size();
			System.out.println("Identifiers loaded from protobuf: " + identifierCount);

			// Step 3: Record jobs before refresh
			int totalBefore = library.getTotalJobs();
			System.out.println("Jobs before refresh: " + totalBefore);

			// Step 4: Call refresh
			System.out.println("Calling refresh()...");
			library.refresh();
			library.awaitRefresh().get(120, TimeUnit.SECONDS);

			int totalAfter = library.getTotalJobs();
			int jobsSubmitted = totalAfter - totalBefore;

			System.out.println("Jobs after refresh: " + totalAfter);
			System.out.println("Jobs submitted by refresh(): " + jobsSubmitted);

			// The protobuf has complete data for all known entries.
			// refresh() may submit a few jobs for genuinely new files on
			// disk that are not in the protobuf, but should NOT recompute
			// the thousands of entries that already have complete data.
			System.out.println("Jobs submitted by refresh(): " + jobsSubmitted +
					" (of " + identifierCount + " known entries)");
			assertTrue(
					"refresh() submitted " + jobsSubmitted +
							" recomputation jobs — far too many for " + identifierCount +
							" entries that already have complete feature data in the protobuf",
					jobsSubmitted < identifierCount * 0.01);
		} finally {
			library.stop();
		}
	}
}
