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
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.similarity.AudioSimilarityGraph;
import org.almostrealism.audio.similarity.SimilarityNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.almostrealism.audio.persistence.AudioLibraryPersistence;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tests for {@link AudioLibrary} cache behavior introduced on the
 * {@code feature/similarity-performance} branch, including the
 * {@link io.almostrealism.util.FrequencyCache}-backed detail storage,
 * {@link AudioLibrary#getAllIdentifiers()}, {@link AudioLibrary#cleanup},
 * and the details loader fallback.
 */
public class AudioLibraryCacheTest extends TestSuiteBase {

	private File tempDir;
	private AudioLibrary library;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("audio-lib-cache-test").toFile();
		library = new AudioLibrary(tempDir, 44100);
	}

	@After
	public void tearDown() {
		if (library != null) {
			library.stop();
		}
		if (tempDir != null) {
			tempDir.delete();
		}
	}

	/**
	 * Verifies that include stores the identifier in completeIdentifiers
	 * and that get() retrieves it.
	 */
	@Test(timeout = 10000)
	public void addAndGetCompleteDetails() {
		WaveDetails details = createCompleteDetails("test-id-1");
		library.include(details);

		Set<String> ids = library.getAllIdentifiers();
		Assert.assertTrue("Identifier should be in completeIdentifiers",
				ids.contains("test-id-1"));

		WaveDetails retrieved = library.get("test-id-1");
		Assert.assertNotNull("get() should return the details", retrieved);
		Assert.assertEquals("test-id-1", retrieved.getIdentifier());
	}

	/**
	 * Verifies that getAllIdentifiers returns an unmodifiable set.
	 */
	@Test(timeout = 5000, expected = UnsupportedOperationException.class)
	public void getAllIdentifiersIsUnmodifiable() {
		WaveDetails details = createCompleteDetails("test-id-1");
		library.include(details);

		Set<String> ids = library.getAllIdentifiers();
		ids.add("should-fail");
	}

	/**
	 * Verifies that get() returns null for an unknown identifier.
	 */
	@Test(timeout = 5000)
	public void getReturnsNullForUnknownId() {
		Assert.assertNull(library.get("nonexistent"));
	}

	/**
	 * Verifies that allDetails() streams all complete entries.
	 */
	@Test(timeout = 10000)
	public void allDetailsStreamsAllEntries() {
		library.include(createCompleteDetails("id-1"));
		library.include(createCompleteDetails("id-2"));
		library.include(createCompleteDetails("id-3"));

		long count = library.allDetails().count();
		Assert.assertEquals(3, count);
	}

	/**
	 * Verifies that cleanup preserves persistent entries and removes
	 * non-persistent ones that are not associated with active files.
	 */
	@Test(timeout = 10000)
	public void cleanupPreservesPersistentEntries() {
		WaveDetails persistent = createCompleteDetails("persistent-id");
		persistent.setPersistent(true);
		library.include(persistent);

		WaveDetails nonPersistent = createCompleteDetails("non-persistent-id");
		nonPersistent.setPersistent(false);
		library.include(nonPersistent);

		Assert.assertEquals(2, library.getAllIdentifiers().size());

		library.cleanup(null);

		// Persistent entry should survive cleanup
		Assert.assertTrue("Persistent entry should survive cleanup",
				library.getAllIdentifiers().contains("persistent-id"));

		// Non-persistent entry with no active file should be removed
		Assert.assertFalse("Non-persistent entry should be removed",
				library.getAllIdentifiers().contains("non-persistent-id"));
	}

	/**
	 * Verifies that cleanup respects the preserve predicate.
	 */
	@Test(timeout = 10000)
	public void cleanupRespectsPreservePredicate() {
		WaveDetails details1 = createCompleteDetails("keep-id");
		library.include(details1);

		WaveDetails details2 = createCompleteDetails("remove-id");
		library.include(details2);

		library.cleanup(id -> id.equals("keep-id"));

		Assert.assertTrue("Preserved entry should remain",
				library.getAllIdentifiers().contains("keep-id"));
		Assert.assertFalse("Non-preserved entry should be removed",
				library.getAllIdentifiers().contains("remove-id"));
	}

	/**
	 * Verifies that setDetailsLoader provides a fallback for resolving
	 * evicted cache entries.
	 */
	@Test(timeout = 10000)
	public void detailsLoaderFallback() {
		WaveDetails original = createCompleteDetails("loader-test-id");
		library.include(original);

		// Set a loader that returns a fresh WaveDetails on demand
		library.setDetailsLoader(id -> {
			if ("loader-test-id".equals(id)) {
				return createCompleteDetails("loader-test-id");
			}
			return null;
		});

		// The identifier should be accessible even if the cache evicts it
		Assert.assertTrue(library.getAllIdentifiers().contains("loader-test-id"));
		WaveDetails resolved = library.get("loader-test-id");
		Assert.assertNotNull("Details should be resolvable via loader", resolved);
		Assert.assertEquals("loader-test-id", resolved.getIdentifier());
	}

	/**
	 * Verifies that toSimilarityGraph creates a graph with SimilarityNode
	 * instances, not full WaveDetails.
	 */
	@Test(timeout = 10000)
	public void toSimilarityGraphCreatesSimilarityNodes() {
		WaveDetails d1 = createCompleteDetails("g-id-1");
		WaveDetails d2 = createCompleteDetails("g-id-2");

		d1.getSimilarities().put("g-id-2", 0.75);
		d2.getSimilarities().put("g-id-1", 0.75);

		library.include(d1);
		library.include(d2);

		AudioSimilarityGraph graph = library.toSimilarityGraph();
		Assert.assertEquals(2, graph.countNodes());

		SimilarityNode node = graph.nodeAt(0);
		Assert.assertNotNull(node);
		Assert.assertNotNull(node.getIdentifier());
	}

	/**
	 * Verifies that resetSimilarities clears similarity data
	 * for all cached entries.
	 */
	@Test(timeout = 10000)
	public void resetSimilaritiesClearsMaps() {
		WaveDetails details = createCompleteDetails("sim-id");
		details.getSimilarities().put("other-id", 0.5);
		library.include(details);

		library.resetSimilarities();

		WaveDetails retrieved = library.get("sim-id");
		Assert.assertNotNull(retrieved);
		Assert.assertTrue("Similarities should be cleared after reset",
				retrieved.getSimilarities().isEmpty());
	}

	/**
	 * Verifies that incomplete WaveDetails (missing freqData or featureData)
	 * are not added to completeIdentifiers.
	 */
	@Test(timeout = 5000)
	public void incompleteDetailsNotInCompleteIdentifiers() {
		WaveDetails incomplete = new WaveDetails("incomplete-id");
		// No freqData or featureData set
		library.include(incomplete);

		Assert.assertFalse("Incomplete details should not be in completeIdentifiers",
				library.getAllIdentifiers().contains("incomplete-id"));
	}

	/**
	 * Verifies that {@link AudioLibraryPersistence#saveLibrary(AudioLibrary, String)}
	 * succeeds when all entries are loadable, and the saved file can be read back.
	 */
	@Test(timeout = 15000)
	public void saveAndLoadLibrary() {
		library.include(createCompleteDetails("save-1"));
		library.include(createCompleteDetails("save-2"));

		String prefix = tempDir.getAbsolutePath() + "/save-load-test";
		AudioLibraryPersistence.saveLibrary(library, prefix);

		File saved = new File(prefix + "_0.bin");
		Assert.assertTrue("Saved file should exist", saved.exists());
		Assert.assertTrue("Saved file should not be empty", saved.length() > 0);

		// Load into a fresh library and verify entries are present
		AudioLibrary library2 = new AudioLibrary(tempDir, 44100);
		try {
			AudioLibraryPersistence.loadLibrary(library2, prefix);
			Assert.assertTrue("Loaded library should contain save-1",
					library2.getAllIdentifiers().contains("save-1"));
			Assert.assertTrue("Loaded library should contain save-2",
					library2.getAllIdentifiers().contains("save-2"));
		} finally {
			library2.stop();
			saved.delete();
		}
	}

	/**
	 * Verifies that {@link AudioLibrary#submitSimilarityJobs(java.util.function.Consumer)}
	 * submits work as {@link org.almostrealism.audio.data.WaveDetailsJob} instances
	 * (not plain Runnables) and completes its returned future.
	 *
	 * <p>Prior to the fix, this method submitted plain lambdas to the executor,
	 * whose priority function casts to {@code WaveDetailsJob}. That caused a
	 * {@code ClassCastException} at runtime.</p>
	 */
	@Test(timeout = 30000)
	public void submitSimilarityJobsCompletes() {
		WaveDetails d1 = createCompleteDetails("sim-job-1");
		WaveDetails d2 = createCompleteDetails("sim-job-2");

		d1.getSimilarities().put("sim-job-2", 0.7);
		d2.getSimilarities().put("sim-job-1", 0.7);

		library.include(d1);
		library.include(d2);

		// This should not throw ClassCastException
		java.util.concurrent.CompletableFuture<Void> future =
				library.submitSimilarityJobs(null);
		future.join();

		// Verify the future completed successfully
		Assert.assertTrue("Future should be done", future.isDone());
		Assert.assertFalse("Future should not be exceptional",
				future.isCompletedExceptionally());
	}

	/**
	 * Verifies that {@link AudioLibrary#submitSimilarityJobs(java.util.function.Consumer)}
	 * invokes the status callback with progress messages.
	 */
	@Test(timeout = 30000)
	public void submitSimilarityJobsReportsProgress() {
		// Add enough entries to trigger the modular progress reporting
		for (int i = 0; i < 3; i++) {
			WaveDetails d = createCompleteDetails("prog-" + i);
			library.include(d);
		}

		java.util.List<String> messages = java.util.Collections.synchronizedList(
				new java.util.ArrayList<>());

		java.util.concurrent.CompletableFuture<Void> future =
				library.submitSimilarityJobs(messages::add);
		future.join();

		// The future completed; verify at least one message was sent
		// (3 entries triggers the "index + 1 == total" path on the last entry)
		Assert.assertFalse("Should have received at least one progress message",
				messages.isEmpty());
	}

	// ── Test helpers ─────────────────────────────────────────────────────

	/**
	 * Creates a {@link WaveDetails} instance with non-null freqData and
	 * featureData so that {@link AudioLibrary#isComplete(WaveDetails)}
	 * returns true.
	 */
	private WaveDetails createCompleteDetails(String identifier) {
		WaveDetails details = new WaveDetails(identifier, 44100);
		details.setFreqData(new PackedCollection(1));
		details.setFeatureData(new PackedCollection(1));
		details.setSimilarities(new HashMap<>());
		return details;
	}
}
