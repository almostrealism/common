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

package org.almostrealism.studio.persistence.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsStore;
import org.almostrealism.audio.similarity.AudioSimilarityGraph;
import org.almostrealism.audio.similarity.PrototypeIndexData;
import org.almostrealism.audio.similarity.SimilarityNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.almostrealism.studio.persistence.AudioLibraryPersistence;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
	 * Verifies that cleanup correctly processes all entries even when
	 * most have been evicted from the in-memory cache. With a cache
	 * capacity of 1000, inserting 1200 entries means at least 200 are
	 * evicted. Cleanup must still preserve persistent entries and
	 * remove non-persistent ones regardless of cache residency.
	 */
	@Test(timeout = 30000)
	public void cleanupCoversAllEntriesBeyondCacheCapacity() {
		int total = AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY + 200;
		int persistentCount = 0;

		for (int i = 0; i < total; i++) {
			WaveDetails details = createCompleteDetails("entry-" + i);
			boolean persistent = (i % 3 == 0);
			details.setPersistent(persistent);
			if (persistent) persistentCount++;
			library.include(details);
		}

		Assert.assertEquals(total, library.getAllIdentifiers().size());
		Assert.assertEquals("All persistent entries should be tracked",
				persistentCount, library.getPersistentIdentifiers().size());

		library.cleanup(null);

		// All persistent entries must survive cleanup
		Assert.assertEquals("All persistent entries should survive cleanup",
				persistentCount, library.getAllIdentifiers().size());

		for (int i = 0; i < total; i++) {
			boolean persistent = (i % 3 == 0);
			Assert.assertEquals(
					"Entry entry-" + i + " (persistent=" + persistent + ") presence mismatch",
					persistent, library.getAllIdentifiers().contains("entry-" + i));
		}
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
		CompletableFuture<Void> future =
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

		List<String> messages = Collections.synchronizedList(
				new ArrayList<>());

		CompletableFuture<Void> future =
				library.submitSimilarityJobs(messages::add);
		future.join();

		// The future completed; verify at least one message was sent
		// (3 entries triggers the "index + 1 == total" path on the last entry)
		Assert.assertFalse("Should have received at least one progress message",
				messages.isEmpty());
	}

	/**
	 * Verifies that {@link AudioLibraryPersistence#saveLibrary(AudioLibrary, String)}
	 * throws {@link IllegalStateException} when some entries cannot be loaded
	 * (e.g., evicted from cache with no details loader configured).
	 */
	@Test(timeout = 15000, expected = IllegalStateException.class)
	public void saveLibraryGuardRejectsUnloadableEntries() {
		int originalCapacity = AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY;
		AudioLibrary smallLib = null;

		try {
			AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = 2;
			smallLib = new AudioLibrary(tempDir, 44100);

			// Add 5 entries; the cache can only hold 2, so 3 will be evicted
			for (int i = 0; i < 5; i++) {
				smallLib.include(createCompleteDetails("guard-" + i));
			}

			// All 5 identifiers are tracked, but only 2 are in cache
			Assert.assertEquals("All identifiers should be tracked",
					5, smallLib.getAllIdentifiers().size());

			String prefix = tempDir.getAbsolutePath() + "/guard-test";

			// Without a details loader, evicted entries cannot be loaded.
			// saveLibrary should throw to prevent data loss.
			AudioLibraryPersistence.saveLibrary(smallLib, prefix);
		} finally {
			AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = originalCapacity;
			if (smallLib != null) smallLib.stop();
		}
	}

	/**
	 * Verifies that {@link AudioLibraryPersistence#loadSingleDetail(String, String)}
	 * can retrieve a specific entry from protobuf files by identifier.
	 */
	@Test(timeout = 15000)
	public void loadSingleDetailFindsEntry() {
		library.include(createCompleteDetails("single-1"));
		library.include(createCompleteDetails("single-2"));
		library.include(createCompleteDetails("single-3"));

		String prefix = tempDir.getAbsolutePath() + "/single-detail-test";
		AudioLibraryPersistence.saveLibrary(library, prefix);

		WaveDetails loaded = AudioLibraryPersistence.loadSingleDetail(prefix, "single-2");
		Assert.assertNotNull("loadSingleDetail should find the entry", loaded);
		Assert.assertEquals("single-2", loaded.getIdentifier());
		Assert.assertNotNull("Loaded entry should have freqData", loaded.getFreqData());
		Assert.assertNotNull("Loaded entry should have featureData", loaded.getFeatureData());

		// Verify that a missing identifier returns null
		WaveDetails missing = AudioLibraryPersistence.loadSingleDetail(prefix, "nonexistent");
		Assert.assertNull("loadSingleDetail should return null for missing id", missing);

		new File(prefix + "_0.bin").delete();
	}

	/**
	 * Verifies that {@link AudioLibraryPersistence#createDetailsLoader(String)}
	 * produces a loader that can restore evicted cache entries from disk,
	 * making all entries resolvable via {@link AudioLibrary#get(String)}.
	 */
	@Test(timeout = 15000)
	public void createDetailsLoaderResolvesEvictedEntries() {
		// Save 5 entries to disk
		for (int i = 0; i < 5; i++) {
			library.include(createCompleteDetails("loader-" + i));
		}

		String prefix = tempDir.getAbsolutePath() + "/loader-test";
		AudioLibraryPersistence.saveLibrary(library, prefix);

		int originalCapacity = AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY;
		AudioLibrary smallLib = null;

		try {
			// Create a library with a small cache (capacity 2)
			AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = 2;
			smallLib = new AudioLibrary(tempDir, 44100);

			// Load from disk — this auto-wires the details loader
			AudioLibraryPersistence.loadLibrary(smallLib, prefix);

			// All 5 identifiers should be tracked
			Assert.assertEquals("All identifiers should be tracked",
					5, smallLib.getAllIdentifiers().size());

			// Even with a small cache, all entries should be resolvable
			// via the auto-wired details loader
			for (int i = 0; i < 5; i++) {
				WaveDetails resolved = smallLib.get("loader-" + i);
				Assert.assertNotNull("Entry loader-" + i + " should be resolvable", resolved);
				Assert.assertEquals("loader-" + i, resolved.getIdentifier());
			}

			// allDetails() should return all 5 entries via the loader
			long count = smallLib.allDetails().count();
			Assert.assertEquals("allDetails() should return all entries via loader",
					5, count);
		} finally {
			AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = originalCapacity;
			if (smallLib != null) smallLib.stop();
			new File(prefix + "_0.bin").delete();
		}
	}

	/**
	 * Verifies that cleanup removes entries from completeIdentifiers when
	 * the entry is evicted from the cache and no details loader is configured.
	 *
	 * <p>Prior to the fix, if {@code resolveDetails(id)} returned null for an
	 * evicted entry, the filter {@code d != null && !d.isPersistent()} evaluated
	 * to false, leaving the entry in completeIdentifiers as a zombie. The fix
	 * changed the condition to {@code d == null || !d.isPersistent()} so that
	 * unresolvable entries are eligible for removal.</p>
	 */
	@Test(timeout = 15000)
	public void cleanupRemovesEvictedEntriesWithoutLoader() {
		int originalCapacity = AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY;
		AudioLibrary smallLib = null;

		try {
			AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = 2;
			smallLib = new AudioLibrary(tempDir, 44100);

			// Add 5 entries; the cache can only hold 2, so 3 will be evicted
			for (int i = 0; i < 5; i++) {
				smallLib.include(createCompleteDetails("zombie-" + i));
			}

			Assert.assertEquals("All 5 identifiers should be tracked before cleanup",
					5, smallLib.getAllIdentifiers().size());

			// No details loader is configured, so evicted entries cannot be loaded.
			// cleanup() should still remove them (they are non-persistent, non-active).
			smallLib.cleanup(null);

			// Only entries still in cache should remain; evicted ones should be gone
			Assert.assertTrue("After cleanup, zombie entries should be removed",
					smallLib.getAllIdentifiers().size() < 5);
		} finally {
			AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = originalCapacity;
			if (smallLib != null) smallLib.stop();
		}
	}

	// ── isPrototypeIndexStale tests ──────────────────────────────────────

	/**
	 * Verifies that {@link AudioLibrary#isPrototypeIndexStale()} returns
	 * {@code true} when no prototype index has been set.
	 */
	@Test(timeout = 5000)
	public void staleWhenNoIndexPresent() {
		library.include(createCompleteDetails("stale-1"));
		Assert.assertTrue("Should be stale when no index is set",
				library.isPrototypeIndexStale());
	}

	/**
	 * Verifies that {@link AudioLibrary#isPrototypeIndexStale()} returns
	 * {@code false} when the index exactly matches the current library contents.
	 */
	@Test(timeout = 5000)
	public void notStaleWhenIndexMatchesLibrary() {
		library.include(createCompleteDetails("idx-1"));
		library.include(createCompleteDetails("idx-2"));

		PrototypeIndexData index = new PrototypeIndexData(
				System.currentTimeMillis(),
				List.of(new PrototypeIndexData.Community(
						"idx-1", 0.5, List.of("idx-1", "idx-2"))));
		library.setPrototypeIndex(index);

		Assert.assertFalse("Should not be stale when index matches library",
				library.isPrototypeIndexStale());
	}

	/**
	 * Verifies that {@link AudioLibrary#isPrototypeIndexStale()} returns
	 * {@code true} when a prototype identifier has been removed from the
	 * library (no longer in completeIdentifiers).
	 */
	@Test(timeout = 5000)
	public void staleWhenPrototypeDeleted() {
		library.include(createCompleteDetails("proto-1"));
		library.include(createCompleteDetails("proto-2"));

		PrototypeIndexData index = new PrototypeIndexData(
				System.currentTimeMillis(),
				List.of(
						new PrototypeIndexData.Community(
								"proto-1", 0.5, List.of("proto-1")),
						new PrototypeIndexData.Community(
								"deleted-proto", 0.3, List.of("deleted-proto"))));
		library.setPrototypeIndex(index);

		Assert.assertTrue("Should be stale when a prototype is not in library",
				library.isPrototypeIndexStale());
	}

	/**
	 * Verifies that {@link AudioLibrary#isPrototypeIndexStale()} returns
	 * {@code true} when more than 5% of indexed members are missing.
	 */
	@Test(timeout = 5000)
	public void staleWhenManyMembersMissing() {
		// Add 10 entries but index references 20, so 10 are missing (50%)
		for (int i = 0; i < 10; i++) {
			library.include(createCompleteDetails("member-" + i));
		}

		List<String> allMembers = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			allMembers.add("member-" + i);
		}

		PrototypeIndexData index = new PrototypeIndexData(
				System.currentTimeMillis(),
				List.of(new PrototypeIndexData.Community(
						"member-0", 0.5, allMembers)));
		library.setPrototypeIndex(index);

		Assert.assertTrue("Should be stale when >5% members missing",
				library.isPrototypeIndexStale());
	}

	/**
	 * Verifies that {@link AudioLibrary#isPrototypeIndexStale()} returns
	 * {@code true} when significantly more samples exist than are indexed
	 * (more than 5% growth).
	 */
	@Test(timeout = 5000)
	public void staleWhenLibraryGrowsSignificantly() {
		// Add 12 entries but index only references 10
		for (int i = 0; i < 12; i++) {
			library.include(createCompleteDetails("grow-" + i));
		}

		List<String> indexedMembers = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			indexedMembers.add("grow-" + i);
		}

		PrototypeIndexData index = new PrototypeIndexData(
				System.currentTimeMillis(),
				List.of(new PrototypeIndexData.Community(
						"grow-0", 0.5, indexedMembers)));
		library.setPrototypeIndex(index);

		Assert.assertTrue("Should be stale when library grows >5% beyond index",
				library.isPrototypeIndexStale());
	}

	/**
	 * Verifies that {@link AudioLibrary#isPrototypeIndexStale()} returns
	 * {@code true} when the index is empty (no communities).
	 */
	@Test(timeout = 5000)
	public void staleWhenIndexHasNoCommunities() {
		library.include(createCompleteDetails("empty-idx-1"));

		PrototypeIndexData index = new PrototypeIndexData(
				System.currentTimeMillis(), List.of());
		library.setPrototypeIndex(index);

		Assert.assertTrue("Should be stale when index has no communities",
				library.isPrototypeIndexStale());
	}

	// ── Store-backed library tests ───────────────────────────────────────

	/**
	 * Verifies that a store-backed library's {@code include()} method
	 * delegates to the store, and that entries are retrievable after
	 * close and reopen.
	 */
	@Test(timeout = 15000)
	public void storeBackedLibraryPersistsEntries() throws IOException {
		File storeDir = new File(tempDir, "store-backed-test");

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir);
		AudioLibrary storeLib = new AudioLibrary(
				new FileWaveDataProviderNode(tempDir),
				44100, store);

		WaveDetails details = createDetailsWithFeatures("store-1", 4, 8);
		storeLib.include(details);

		Assert.assertTrue("Store should contain the entry",
				store.containsKey("store-1"));
		Assert.assertEquals("Library should track the identifier",
				1, storeLib.getAllIdentifiers().size());

		storeLib.stop();
		store.close();

		ProtobufWaveDetailsStore store2 = new ProtobufWaveDetailsStore(storeDir);
		Assert.assertEquals("Reopened store should have 1 record", 1, store2.size());

		WaveDetails reloaded = store2.get("store-1");
		Assert.assertNotNull("Reloaded details should not be null", reloaded);
		Assert.assertEquals("store-1", reloaded.getIdentifier());
		Assert.assertNotNull("Feature data should survive persistence",
				reloaded.getFeatureData());

		store2.close();
	}

	/**
	 * Verifies that {@link ProtobufWaveDetailsStore#put(String, WaveDetails, PackedCollection)}
	 * stores the embedding vector and makes it searchable via
	 * {@link ProtobufWaveDetailsStore#searchNeighbors(PackedCollection, int)}.
	 */
	@Test(timeout = 15000)
	public void putWithEmbeddingEnablesSearch() throws IOException {
		File storeDir = new File(tempDir, "embedding-search-test");

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir);

		for (int i = 0; i < 5; i++) {
			WaveDetails details = createDetailsWithFeatures("emb-" + i, 4, 8);
			PackedCollection embedding = AudioLibrary.computeEmbeddingVector(details);
			Assert.assertNotNull("Embedding should be computable", embedding);
			store.put("emb-" + i, details, embedding);
		}

		WaveDetails query = store.get("emb-2");
		Assert.assertNotNull(query);
		PackedCollection queryEmbedding = AudioLibrary.computeEmbeddingVector(query);

		List<WaveDetailsStore.NeighborResult> neighbors =
				store.searchNeighbors(queryEmbedding, 3);
		Assert.assertFalse("Search should return results", neighbors.isEmpty());
		Assert.assertTrue("Should find at least 2 neighbors",
				neighbors.size() >= 2);

		Set<String> neighborIds = new HashSet<>();
		for (WaveDetailsStore.NeighborResult n : neighbors) {
			neighborIds.add(n.identifier());
			Assert.assertTrue("Similarity should be between 0 and 1",
					n.similarity() >= 0.0f && n.similarity() <= 1.001f);
		}

		store.close();
	}

	/**
	 * Verifies that {@link AudioLibrary#computeEmbeddingVector(WaveDetails)}
	 * correctly mean-pools feature data across frames and returns a vector
	 * with the expected number of bins.
	 */
	@Test(timeout = 5000)
	public void computeEmbeddingVectorMeanPoolsFrames() {
		int frames = 3;
		int bins = 4;
		WaveDetails details = new WaveDetails("embed-test", 44100);
		PackedCollection featureData = new PackedCollection(frames, bins, 1);

		// Frame 0: [1, 2, 3, 4]
		// Frame 1: [5, 6, 7, 8]
		// Frame 2: [3, 3, 3, 3]
		// Mean:    [3, 3.667, 4.333, 5]
		featureData.setMem(0, 1.0); featureData.setMem(1, 2.0);
		featureData.setMem(2, 3.0); featureData.setMem(3, 4.0);
		featureData.setMem(4, 5.0); featureData.setMem(5, 6.0);
		featureData.setMem(6, 7.0); featureData.setMem(7, 8.0);
		featureData.setMem(8, 3.0); featureData.setMem(9, 3.0);
		featureData.setMem(10, 3.0); featureData.setMem(11, 3.0);
		details.setFeatureData(featureData);

		PackedCollection embedding = AudioLibrary.computeEmbeddingVector(details);
		Assert.assertNotNull("Should produce an embedding", embedding);
		Assert.assertEquals("Embedding should have bins dimensions",
				bins, embedding.getMemLength());

		Assert.assertEquals("Bin 0 mean should be 3.0",
				3.0, embedding.toDouble(0), 1e-9);
		Assert.assertEquals("Bin 1 mean should be ~3.667",
				11.0 / 3.0, embedding.toDouble(1), 1e-9);
		Assert.assertEquals("Bin 2 mean should be ~4.333",
				13.0 / 3.0, embedding.toDouble(2), 1e-9);
		Assert.assertEquals("Bin 3 mean should be 5.0",
				5.0, embedding.toDouble(3), 1e-9);
	}

	/**
	 * Verifies that {@link AudioLibrary#computeEmbeddingVector(WaveDetails)}
	 * returns null when details have no feature data.
	 */
	@Test(timeout = 5000)
	public void computeEmbeddingVectorReturnsNullForNoFeatures() {
		WaveDetails noFeatures = new WaveDetails("no-feat", 44100);
		Assert.assertNull("Should return null for no feature data",
				AudioLibrary.computeEmbeddingVector(noFeatures));

		Assert.assertNull("Should return null for null details",
				AudioLibrary.computeEmbeddingVector(null));
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

	/**
	 * Creates a {@link WaveDetails} with multi-frame feature data.
	 */
	private WaveDetails createDetailsWithFeatures(String identifier,
												  int frames, int bins) {
		WaveDetails details = new WaveDetails(identifier, 44100);
		details.setFreqData(new PackedCollection(bins));

		PackedCollection featureData = new PackedCollection(frames, bins, 1);
		for (int f = 0; f < frames; f++) {
			for (int b = 0; b < bins; b++) {
				featureData.setMem(f * bins + b,
						(identifier.hashCode() + f * bins + b) * 0.001);
			}
		}
		details.setFeatureData(featureData);
		details.setFeatureSampleRate(16.0);
		details.setFeatureChannelCount(1);
		details.setSimilarities(new HashMap<>());
		return details;
	}
}
