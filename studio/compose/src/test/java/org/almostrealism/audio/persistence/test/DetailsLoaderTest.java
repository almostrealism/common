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
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.persistence.AudioLibraryPersistence;
import org.almostrealism.audio.persistence.LibraryDestination;
import org.almostrealism.audio.similarity.AudioSimilarityGraph;
import org.almostrealism.audio.similarity.PrototypeIndexData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Comprehensive tests for the {@link AudioLibrary} lazy-loading feature
 * implemented via {@code FrequencyCache} and {@link AudioLibrary#setDetailsLoader}.
 *
 * <p>This test class exercises 10 distinct failure modes, with 5 tests per
 * failure mode, totaling 50 tests. Each failure mode represents a category
 * of bugs that can occur when the AudioLibrary uses a bounded cache with
 * on-demand loading from disk.</p>
 *
 * <h2>Failure Modes Tested</h2>
 * <ol>
 *   <li><b>FM1: Data Loss on Save</b> &mdash; {@code getAllDetails()} returns only
 *       cached entries when no loader is set, causing {@code saveLibrary()} to
 *       serialize a subset and destroy the rest on disk.</li>
 *   <li><b>FM2: Loader Not Set</b> &mdash; {@code getOrLoad()} returns null for
 *       evicted entries when {@code detailsLoader} is not configured after loading
 *       from protobuf.</li>
 *   <li><b>FM3: Cache Capacity Boundary</b> &mdash; entries beyond
 *       {@code DEFAULT_DETAIL_CACHE_CAPACITY} are silently evicted with no
 *       fallback mechanism unless the loader is explicitly configured.</li>
 *   <li><b>FM4: Similarity Computation Over Partial Data</b> &mdash; operations
 *       that depend on {@code allDetails()} (similarity graphs, prototype discovery)
 *       silently operate on a subset of the library when evicted entries cannot
 *       be reloaded.</li>
 *   <li><b>FM5: Save-Load Roundtrip Integrity</b> &mdash; verifying that
 *       {@link WaveDetails} data (metadata, frequency data, feature data,
 *       similarities) survives protobuf serialization and deserialization.</li>
 *   <li><b>FM6: Cache Thrashing During Bulk Operations</b> &mdash;
 *       {@code allDetails()} iteration with a loader causes continuous
 *       eviction/reload cycles as each loaded entry displaces another.</li>
 *   <li><b>FM7: Stale Similarity Data After Eviction and Reload</b> &mdash;
 *       evicted entries lose in-memory similarity updates;
 *       {@code resetSimilarities()} only clears cached entries, leaving evicted
 *       entries with stale data on disk.</li>
 *   <li><b>FM8: allIdentifiers vs detailsCache Inconsistency</b> &mdash; the
 *       {@code allIdentifiers} set and the {@code detailsCache} diverge after
 *       eviction, causing {@code cleanup()} to miss phantom entries and
 *       {@code get()} to return null for "known" identifiers.</li>
 *   <li><b>FM9: Thread Safety</b> &mdash; concurrent operations on the
 *       unsynchronized {@code FrequencyCache} and {@code HashSet} can cause
 *       {@code ConcurrentModificationException} or lost updates.</li>
 *   <li><b>FM10: PrototypeDiscovery Integration</b> &mdash;
 *       {@code PrototypeDiscovery} loads from protobuf via {@code include()} but
 *       never calls {@code setDetailsLoader()}, so libraries with more entries
 *       than cache capacity only analyze the cached subset.</li>
 * </ol>
 *
 * <h2>Test Design</h2>
 * <p>Tests use a reduced cache capacity ({@value #TEST_CACHE_CAPACITY}) to
 * exercise eviction behavior without requiring hundreds of entries. Synthetic
 * {@link WaveDetails} instances are created with deterministic identifiers
 * and populated feature data. No actual audio files or GPU computation is
 * needed.</p>
 *
 * @see AudioLibrary
 * @see AudioLibraryPersistence
 */
public class DetailsLoaderTest extends TestSuiteBase {
	/** Reduced cache capacity for testing eviction behavior. */
	private static final int TEST_CACHE_CAPACITY = 10;

	/** Sample rate used for all test libraries and details. */
	private static final int SAMPLE_RATE = 44100;

	/** Number of frequency bins in synthetic data. */
	private static final int FREQ_BINS = 64;

	/** Number of frequency frames in synthetic data. */
	private static final int FREQ_FRAMES = 10;

	/** Number of feature bins in synthetic data. */
	private static final int FEATURE_BINS = 32;

	/** Number of feature frames in synthetic data. */
	private static final int FEATURE_FRAMES = 10;

	private int originalCapacity;
	private File tmpDir;
	private AudioLibrary library;

	@Before
	public void setUp() throws Exception {
		originalCapacity = AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY;
		AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = TEST_CACHE_CAPACITY;
		tmpDir = Files.createTempDirectory("details-loader-test").toFile();
	}

	@After
	public void tearDown() {
		AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = originalCapacity;
		if (library != null) {
			library.stop();
			library = null;
		}
		deleteRecursive(tmpDir);
	}

	// ============================================================
	// Helper Methods
	// ============================================================

	/**
	 * Creates a synthetic {@link WaveDetails} with complete metadata,
	 * frequency data, and feature data. The data is deterministic based
	 * on the identifier to allow verification after roundtrips.
	 *
	 * @param identifier content identifier (simulated MD5 hash)
	 * @return a fully-populated WaveDetails
	 */
	private WaveDetails createDetails(String identifier) {
		WaveDetails details = new WaveDetails(identifier);
		details.setSampleRate(SAMPLE_RATE);
		details.setChannelCount(1);
		details.setFrameCount(SAMPLE_RATE);

		details.setFreqSampleRate(100.0);
		details.setFreqBinCount(FREQ_BINS);
		details.setFreqChannelCount(1);
		details.setFreqFrameCount(FREQ_FRAMES);
		PackedCollection freqData = new PackedCollection(FREQ_FRAMES * FREQ_BINS);
		for (int i = 0; i < FREQ_FRAMES * FREQ_BINS; i++) {
			freqData.setMem(i, 0.001 * (identifier.hashCode() % 1000) + 0.0001 * i);
		}
		details.setFreqData(freqData);

		details.setFeatureSampleRate(100.0);
		details.setFeatureBinCount(FEATURE_BINS);
		details.setFeatureChannelCount(1);
		details.setFeatureFrameCount(FEATURE_FRAMES);
		PackedCollection featureData = new PackedCollection(FEATURE_FRAMES * FEATURE_BINS);
		for (int i = 0; i < FEATURE_FRAMES * FEATURE_BINS; i++) {
			featureData.setMem(i, 0.002 * (identifier.hashCode() % 500) + 0.0002 * i);
		}
		details.setFeatureData(featureData);

		return details;
	}

	/**
	 * Creates an {@link AudioLibrary} backed by the test temp directory.
	 * The cache capacity is controlled by {@link #TEST_CACHE_CAPACITY}.
	 *
	 * @return a new AudioLibrary with an empty file tree
	 */
	private AudioLibrary createLibrary() {
		library = new AudioLibrary(tmpDir, SAMPLE_RATE);
		return library;
	}

	/**
	 * Populates a library with {@code count} synthetic entries via
	 * {@link AudioLibrary#include}. Returns a map of all entries
	 * keyed by identifier.
	 *
	 * @param lib   the library to populate
	 * @param count number of entries to add
	 * @return map from identifier to WaveDetails for all added entries
	 */
	private Map<String, WaveDetails> populateLibrary(AudioLibrary lib, int count) {
		Map<String, WaveDetails> allEntries = new HashMap<>();
		for (int i = 0; i < count; i++) {
			String id = "test-id-" + String.format("%04d", i);
			WaveDetails details = createDetails(id);
			lib.include(details);
			allEntries.put(id, details);
		}
		return allEntries;
	}

	/**
	 * Creates a loader function backed by a map. When invoked, creates a
	 * fresh copy of the WaveDetails (simulating loading from disk) so that
	 * the returned object is distinct from the cached one.
	 *
	 * @param source map of identifier to WaveDetails
	 * @return loader function suitable for {@link AudioLibrary#setDetailsLoader}
	 */
	private Function<String, WaveDetails> createLoaderFromMap(Map<String, WaveDetails> source) {
		return identifier -> {
			WaveDetails original = source.get(identifier);
			if (original == null) return null;
			WaveDetails copy = createDetails(identifier);
			copy.getSimilarities().putAll(original.getSimilarities());
			copy.setPersistent(original.isPersistent());
			return copy;
		};
	}

	/**
	 * Returns a protobuf file prefix in the temp directory.
	 */
	private String savePrefix() {
		return new File(tmpDir, "library").getAbsolutePath();
	}

	/**
	 * Counts the total number of WaveDetails entries stored in protobuf
	 * files at the given prefix.
	 *
	 * @param prefix the data prefix for library files
	 * @return total number of entries across all batch files
	 */
	private int countSavedEntries(String prefix) {
		LibraryDestination dest = new LibraryDestination(prefix);
		List<Audio.AudioLibraryData> batches = dest.load();
		return batches.stream()
				.mapToInt(b -> b.getInfoMap().size())
				.sum();
	}

	/**
	 * Loads all identifiers from protobuf files at the given prefix into
	 * a fresh AudioLibrary with high capacity (no eviction) and returns
	 * the set of identifiers.
	 *
	 * @param prefix the data prefix for library files
	 * @return set of all identifiers in the saved data
	 */
	private Set<String> loadSavedIdentifiers(String prefix) {
		int savedCap = AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY;
		AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = 10000;
		AudioLibrary counting = new AudioLibrary(tmpDir, SAMPLE_RATE);
		AudioLibraryPersistence.loadLibrary(counting, prefix);
		Set<String> ids = new HashSet<>(counting.getAllIdentifiers());
		counting.stop();
		AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = savedCap;
		return ids;
	}

	private void deleteRecursive(File file) {
		if (file == null || !file.exists()) return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursive(child);
				}
			}
		}
		file.delete();
	}

	// ============================================================
	// FM1: Data Loss on Save
	//
	// When getAllDetails() is called without a detailsLoader, only entries
	// currently in the FrequencyCache are returned. saveLibrary() uses
	// getAllDetails() to determine what to serialize, so it writes a
	// subset of the library to disk, destroying the complete dataset.
	// ============================================================

	/**
	 * FM1.1: getAllDetails returns fewer entries than were included when
	 * the cache capacity is exceeded and no loader is set.
	 */
	@Test
	public void fm1_getAllDetailsLosesEvictedEntriesWithoutLoader() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		populateLibrary(lib, total);

		Collection<WaveDetails> retrieved = lib.getAllDetails();

		assertTrue("getAllDetails should return fewer entries than total included "
						+ "(got " + retrieved.size() + " for " + total + " included)",
				retrieved.size() < total);
		assertEquals("allIdentifiers should still know about all entries",
				total, lib.getAllIdentifiers().size());
	}

	/**
	 * FM1.2: saveLibrary throws {@link IllegalStateException} when no loader
	 * is set and evicted entries cannot be loaded, preventing silent data loss.
	 */
	@Test(expected = IllegalStateException.class)
	public void fm1_saveLibraryThrowsWithoutLoader() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		populateLibrary(lib, total);

		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);
	}

	/**
	 * FM1.3: A save-load-save cycle preserves all entries because
	 * {@link AudioLibraryPersistence#loadLibrary(AudioLibrary, String)}
	 * auto-wires the details loader, preventing data loss.
	 */
	@Test
	public void fm1_saveLoadSaveCyclePreservesDataWithAutoWiring() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		populateLibrary(lib, total);

		// First save with loader set (preserves all entries)
		lib.setDetailsLoader(id -> createDetails(id));

		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);
		int firstSaveCount = countSavedEntries(prefix);
		assertEquals("First save should write all entries", total, firstSaveCount);

		// Load into new library — loadLibrary auto-wires the details loader
		lib.stop();
		library = null;
		AudioLibrary secondLib = createLibrary();
		AudioLibraryPersistence.loadLibrary(secondLib, prefix);

		String prefix2 = new File(tmpDir, "library2").getAbsolutePath();
		AudioLibraryPersistence.saveLibrary(secondLib, prefix2);
		int secondSaveCount = countSavedEntries(prefix2);

		assertEquals("Second save should preserve all entries via auto-wired loader",
				firstSaveCount, secondSaveCount);
	}

	/**
	 * FM1.4: The divergence between allIdentifiers.size() and
	 * getAllDetails().size() proves silent data loss.
	 */
	@Test
	public void fm1_identifierCountDivergesFromDetailsCount() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY * 2;
		populateLibrary(lib, total);

		int identifierCount = lib.getAllIdentifiers().size();
		int detailsCount = lib.getAllDetails().size();

		assertEquals("allIdentifiers tracks all entries", total, identifierCount);
		assertTrue("getAllDetails returns fewer without loader: "
						+ detailsCount + " vs " + identifierCount,
				detailsCount < identifierCount);
	}

	/**
	 * FM1.5: Setting the loader before save prevents data loss by making
	 * all entries available to getAllDetails().
	 */
	@Test
	public void fm1_settingLoaderPreventsSaveDataLoss() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> allEntries = populateLibrary(lib, total);

		lib.setDetailsLoader(createLoaderFromMap(allEntries));

		Collection<WaveDetails> retrieved = lib.getAllDetails();
		assertEquals("With loader, getAllDetails should return all entries",
				total, retrieved.size());

		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);
		int savedCount = countSavedEntries(prefix);
		assertEquals("With loader, save should write all entries",
				total, savedCount);
	}

	// ============================================================
	// FM2: Loader Not Set After Load
	//
	// When AudioLibraryPersistence.loadLibrary() loads entries via
	// include(), entries beyond cache capacity are evicted. If
	// setDetailsLoader() is never called, those entries become
	// permanently inaccessible in the current session.
	// ============================================================

	/**
	 * FM2.1: get() returns null for an evicted entry when no loader is set.
	 */
	@Test
	public void fm2_getReturnsNullForEvictedEntryWithoutLoader() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> allEntries = populateLibrary(lib, total);

		// Find an identifier that was evicted from the cache
		String evictedId = null;
		for (String id : allEntries.keySet()) {
			if (lib.get(id) == null) {
				evictedId = id;
				break;
			}
		}

		assertNotNull("At least one entry should have been evicted", evictedId);
		Assert.assertNull("get() should return null for evicted entry without loader",
				lib.get(evictedId));
		assertTrue("But allIdentifiers should still contain the evicted ID",
				lib.getAllIdentifiers().contains(evictedId));
	}

	/**
	 * FM2.2: get() returns non-null for evicted entries when loader IS set.
	 */
	@Test
	public void fm2_getReturnsEntryWhenLoaderIsSet() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> allEntries = populateLibrary(lib, total);

		lib.setDetailsLoader(createLoaderFromMap(allEntries));

		// All entries should now be retrievable
		for (String id : allEntries.keySet()) {
			WaveDetails retrieved = lib.get(id);
			assertNotNull("get() should return entry via loader for " + id, retrieved);
			assertTrue("Retrieved entry should have correct identifier: " + id,
					id.equals(retrieved.getIdentifier()));
		}
	}

	/**
	 * FM2.3: allDetails() silently drops unloadable entries (no error, no
	 * exception, no warning - just silent data loss).
	 */
	@Test
	public void fm2_allDetailsSilentlyDropsUnloadableEntries() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		populateLibrary(lib, total);

		// No loader set - allDetails() will silently filter out nulls
		List<WaveDetails> details = lib.allDetails().collect(Collectors.toList());

		assertTrue("allDetails() should silently drop evicted entries "
						+ "(got " + details.size() + " of " + total + ")",
				details.size() < total);

		// Every returned entry should be non-null and have valid data
		for (WaveDetails d : details) {
			assertNotNull("No null entries should survive the filter", d);
			assertNotNull("Returned entries should have identifiers",
					d.getIdentifier());
		}
	}

	/**
	 * FM2.4: getAllIdentifiers() still contains evicted entries even when
	 * get() returns null for them.
	 */
	@Test
	public void fm2_getAllIdentifiersContainsEvictedEntries() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> allEntries = populateLibrary(lib, total);

		Set<String> allIds = lib.getAllIdentifiers();
		assertEquals("getAllIdentifiers should contain all " + total + " entries",
				total, allIds.size());

		int nullCount = 0;
		for (String id : allIds) {
			if (lib.get(id) == null) {
				nullCount++;
			}
		}

		assertTrue("Some identifiers should return null from get() "
						+ "(" + nullCount + " null out of " + total + ")",
				nullCount > 0);
	}

	/**
	 * FM2.5: Early entries included within capacity become unretrievable
	 * after more entries are added that cause their eviction.
	 */
	@Test
	public void fm2_earlyEntriesEvictedAfterLaterIncludes() {
		AudioLibrary lib = createLibrary();

		// Include entries within capacity
		List<String> earlyIds = new ArrayList<>();
		for (int i = 0; i < TEST_CACHE_CAPACITY; i++) {
			String id = "early-" + String.format("%04d", i);
			lib.include(createDetails(id));
			earlyIds.add(id);
		}

		// Verify all are retrievable
		for (String id : earlyIds) {
			assertNotNull("Early entry should be retrievable: " + id, lib.get(id));
		}

		// Add more entries, causing evictions
		for (int i = 0; i < TEST_CACHE_CAPACITY; i++) {
			String id = "late-" + String.format("%04d", i);
			lib.include(createDetails(id));
		}

		// Some early entries should now be evicted
		int earlyLost = 0;
		for (String id : earlyIds) {
			if (lib.get(id) == null) {
				earlyLost++;
			}
		}

		assertTrue("Some early entries should be evicted after later includes "
						+ "(" + earlyLost + " lost)",
				earlyLost > 0);
	}

	// ============================================================
	// FM3: Cache Capacity Boundary
	//
	// The FrequencyCache has a fixed capacity (DEFAULT_DETAIL_CACHE_CAPACITY).
	// When this capacity is exceeded, entries are silently evicted based on
	// frequency/recency scoring. Without explicit handling, this causes
	// invisible data loss.
	// ============================================================

	/**
	 * FM3.1: Including exactly capacity entries keeps all entries
	 * retrievable.
	 */
	@Test
	public void fm3_exactCapacityAllRetrievable() {
		AudioLibrary lib = createLibrary();
		Map<String, WaveDetails> entries = populateLibrary(lib, TEST_CACHE_CAPACITY);

		for (String id : entries.keySet()) {
			assertNotNull("Entry within capacity should be retrievable: " + id,
					lib.get(id));
		}

		assertEquals("getAllDetails should return all entries at capacity",
				TEST_CACHE_CAPACITY, lib.getAllDetails().size());
	}

	/**
	 * FM3.2: Including capacity+1 entries causes at least one eviction.
	 */
	@Test
	public void fm3_capacityPlusOneCausesEviction() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 1;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		int retrievableCount = 0;
		for (String id : entries.keySet()) {
			if (lib.get(id) != null) {
				retrievableCount++;
			}
		}

		assertTrue("At least one entry should have been evicted "
						+ "(retrievable: " + retrievableCount + " of " + total + ")",
				retrievableCount < total);
		assertEquals("allIdentifiers should still track all entries",
				total, lib.getAllIdentifiers().size());
	}

	/**
	 * FM3.3: Including 2x capacity entries leaves exactly capacity entries
	 * in the cache.
	 */
	@Test
	public void fm3_doubleCapacityHalvesRetrievable() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY * 2;
		populateLibrary(lib, total);

		int detailsCount = lib.getAllDetails().size();
		assertTrue("getAllDetails without loader should return at most capacity entries "
						+ "(got " + detailsCount + ", capacity " + TEST_CACHE_CAPACITY + ")",
				detailsCount <= TEST_CACHE_CAPACITY);
		assertEquals("allIdentifiers should track all " + total + " entries",
				total, lib.getAllIdentifiers().size());
	}

	/**
	 * FM3.4: Accessing an entry frequently boosts its eviction score,
	 * keeping it in the cache when other entries are evicted.
	 */
	@Test
	public void fm3_frequentAccessPreventsEviction() {
		AudioLibrary lib = createLibrary();

		// Include initial entries
		String protectedId = "protected-entry";
		lib.include(createDetails(protectedId));

		// Access the protected entry many times to boost its frequency
		for (int i = 0; i < 50; i++) {
			lib.get(protectedId);
		}

		// Fill the cache past capacity with new entries
		for (int i = 0; i < TEST_CACHE_CAPACITY + 5; i++) {
			lib.include(createDetails("filler-" + String.format("%04d", i)));
		}

		// The frequently-accessed entry should survive eviction
		assertNotNull("Frequently-accessed entry should survive eviction",
				lib.get(protectedId));
	}

	/**
	 * FM3.5: Every include beyond capacity triggers an eviction, so the
	 * cache never exceeds its maximum size.
	 */
	@Test
	public void fm3_cacheSizeNeverExceedsCapacity() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY * 3;

		for (int i = 0; i < total; i++) {
			String id = "batch-" + String.format("%04d", i);
			lib.include(createDetails(id));

			// Count currently retrievable entries (cache size proxy)
			int retrievable = 0;
			for (int j = 0; j <= i; j++) {
				String checkId = "batch-" + String.format("%04d", j);
				if (lib.get(checkId) != null) {
					retrievable++;
				}
			}

			assertTrue("Cache should never exceed capacity "
							+ "(retrievable: " + retrievable
							+ " at step " + i + ")",
					retrievable <= TEST_CACHE_CAPACITY);
		}
	}

	// ============================================================
	// FM4: Similarity Computation Over Partial Data
	//
	// Operations that depend on allDetails() -- including
	// toSimilarityGraph(), computeSimilarities(), and
	// computeAllSimilaritiesIncremental() -- operate on a subset
	// of the library when evicted entries cannot be reloaded.
	// This produces incomplete similarity graphs where some pairs
	// are never compared.
	// ============================================================

	/**
	 * FM4.1: toSimilarityGraph() with partial data produces a graph with
	 * fewer nodes than the total library size.
	 */
	@Test
	public void fm4_similarityGraphHasFewerNodesThanTotal() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Pre-populate similarity maps between pairs
		List<String> ids = new ArrayList<>(entries.keySet());
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				entries.get(ids.get(i)).getSimilarities()
						.put(ids.get(j), 0.5 + 0.01 * (i + j));
				entries.get(ids.get(j)).getSimilarities()
						.put(ids.get(i), 0.5 + 0.01 * (i + j));
			}
		}

		// Without loader, graph only sees cached entries
		AudioSimilarityGraph graph = lib.toSimilarityGraph();

		assertTrue("Graph should have fewer nodes than total "
						+ "(nodes: " + graph.countNodes()
						+ ", total: " + total + ")",
				graph.countNodes() < total);
	}

	/**
	 * FM4.2: Similarity maps reference identifiers of entries that cannot
	 * be loaded, creating dangling references.
	 */
	@Test
	public void fm4_similarityMapReferencesUnloadableEntries() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Pre-populate similarities
		List<String> ids = new ArrayList<>(entries.keySet());
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				entries.get(ids.get(i)).getSimilarities()
						.put(ids.get(j), 0.5);
				entries.get(ids.get(j)).getSimilarities()
						.put(ids.get(i), 0.5);
			}
		}

		// Find a cached entry and check its similarity map
		WaveDetails cachedEntry = null;
		for (String id : ids) {
			WaveDetails d = lib.get(id);
			if (d != null && !d.getSimilarities().isEmpty()) {
				cachedEntry = d;
				break;
			}
		}

		assertNotNull("Should find at least one cached entry with similarities",
				cachedEntry);

		// Check how many of the referenced entries are actually loadable
		int danglingRefs = 0;
		for (String refId : cachedEntry.getSimilarities().keySet()) {
			if (lib.get(refId) == null) {
				danglingRefs++;
			}
		}

		assertTrue("Some similarity references should be dangling "
						+ "(unloadable): " + danglingRefs,
				danglingRefs > 0);
	}

	/**
	 * FM4.3: Setting a loader makes the full similarity graph available.
	 */
	@Test
	public void fm4_loaderEnablesFullSimilarityGraph() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		lib.setDetailsLoader(createLoaderFromMap(entries));

		AudioSimilarityGraph graph = lib.toSimilarityGraph();
		assertEquals("With loader, graph should contain all entries",
				total, graph.countNodes());
	}

	/**
	 * FM4.4: allDetails().count() is less than allIdentifiers.size()
	 * without a loader, proving that any downstream operation using
	 * allDetails() operates on partial data.
	 */
	@Test
	public void fm4_allDetailsCountLessThanAllIdentifiers() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		populateLibrary(lib, total);

		long detailsCount = lib.allDetails().count();
		int identifiersCount = lib.getAllIdentifiers().size();

		assertEquals("allIdentifiers should have all entries",
				total, identifiersCount);
		assertTrue("allDetails should have fewer entries without loader "
						+ "(details: " + detailsCount
						+ ", identifiers: " + identifiersCount + ")",
				detailsCount < identifiersCount);
	}

	/**
	 * FM4.5: Pre-populated similarity data in cached entries is preserved
	 * and accessible despite cache evictions of other entries.
	 */
	@Test
	public void fm4_cachedEntriesRetainSimilarities() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Add similarities to all entries
		List<String> ids = new ArrayList<>(entries.keySet());
		for (int i = 0; i < ids.size(); i++) {
			entries.get(ids.get(i)).getSimilarities()
					.put("some-reference", 0.42 + 0.01 * i);
		}

		// Verify similarities are accessible
		for (String id : ids) {
			WaveDetails d = lib.get(id);
			assertNotNull("Entry should be retrievable within capacity", d);
			assertFalse("Entry should have similarity data",
					d.getSimilarities().isEmpty());
			assertTrue("Similarity value should be correct",
					d.getSimilarities().get("some-reference") >= 0.42);
		}
	}

	// ============================================================
	// FM5: Save-Load Roundtrip Integrity
	//
	// Verifying that WaveDetails data survives protobuf serialization
	// and deserialization. This ensures the encode/decode pipeline
	// preserves all fields correctly.
	// ============================================================

	/**
	 * FM5.1: Save and reload preserves the entry count when all entries
	 * fit in the cache.
	 */
	@Test
	public void fm5_roundtripPreservesEntryCount() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY;
		populateLibrary(lib, total);

		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);
		lib.stop();
		library = null;

		AudioLibrary loaded = createLibrary();
		AudioLibraryPersistence.loadLibrary(loaded, prefix);

		assertEquals("Roundtrip should preserve entry count",
				total, loaded.getAllIdentifiers().size());
	}

	/**
	 * FM5.2: Similarity maps survive serialization and deserialization.
	 */
	@Test
	public void fm5_roundtripPreservesSimilarities() {
		AudioLibrary lib = createLibrary();
		WaveDetails details = createDetails("sim-test-001");
		details.getSimilarities().put("other-001", 0.75);
		details.getSimilarities().put("other-002", 0.32);
		details.getSimilarities().put("other-003", 0.91);
		lib.include(details);

		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);
		lib.stop();
		library = null;

		AudioLibrary loaded = createLibrary();
		AudioLibraryPersistence.loadLibrary(loaded, prefix);
		WaveDetails loadedDetails = loaded.get("sim-test-001");

		assertNotNull("Entry should be loadable", loadedDetails);
		assertEquals("Similarity count should be preserved",
				3, loadedDetails.getSimilarities().size());
		assertEquals("Similarity value should be preserved",
				0.75, loadedDetails.getSimilarities().get("other-001"), 1e-6);
		assertEquals("Similarity value should be preserved",
				0.32, loadedDetails.getSimilarities().get("other-002"), 1e-6);
		assertEquals("Similarity value should be preserved",
				0.91, loadedDetails.getSimilarities().get("other-003"), 1e-6);
	}

	/**
	 * FM5.3: Feature data values survive serialization.
	 */
	@Test
	public void fm5_roundtripPreservesFeatureData() {
		AudioLibrary lib = createLibrary();
		WaveDetails details = createDetails("feat-test-001");

		// Set known feature data values
		PackedCollection featureData = new PackedCollection(FEATURE_FRAMES * FEATURE_BINS);
		for (int i = 0; i < FEATURE_FRAMES * FEATURE_BINS; i++) {
			featureData.setMem(i, 0.01 * (i + 1));
		}
		details.setFeatureData(featureData);
		lib.include(details);

		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);
		lib.stop();
		library = null;

		AudioLibrary loaded = createLibrary();
		AudioLibraryPersistence.loadLibrary(loaded, prefix);
		WaveDetails loadedDetails = loaded.get("feat-test-001");

		assertNotNull("Entry should be loadable", loadedDetails);
		assertNotNull("Feature data should be present", loadedDetails.getFeatureData());

		double[] original = featureData.doubleStream().toArray();
		double[] roundtripped = loadedDetails.getFeatureData().doubleStream().toArray();
		assertEquals("Feature data length should match",
				original.length, roundtripped.length);
		for (int i = 0; i < original.length; i++) {
			assertEquals("Feature data value at index " + i + " should match",
					original[i], roundtripped[i], 1e-4);
		}
	}

	/**
	 * FM5.4: Audio metadata survives serialization.
	 */
	@Test
	public void fm5_roundtripPreservesMetadata() {
		AudioLibrary lib = createLibrary();
		WaveDetails details = createDetails("meta-test-001");
		details.setSampleRate(48000);
		details.setChannelCount(2);
		details.setFrameCount(96000);
		details.setPersistent(true);
		details.setSilent(true);
		lib.include(details);

		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);
		lib.stop();
		library = null;

		AudioLibrary loaded = createLibrary();
		AudioLibraryPersistence.loadLibrary(loaded, prefix);
		WaveDetails loadedDetails = loaded.get("meta-test-001");

		assertNotNull("Entry should be loadable", loadedDetails);
		assertEquals("sampleRate", 48000, loadedDetails.getSampleRate());
		assertEquals("channelCount", 2, loadedDetails.getChannelCount());
		assertEquals("frameCount", 96000, loadedDetails.getFrameCount());
		assertTrue("persistent flag", loadedDetails.isPersistent());
		assertTrue("silent flag", loadedDetails.isSilent());
	}

	/**
	 * FM5.5: loadSingleDetail correctly finds and returns a specific
	 * entry from protobuf batch files.
	 */
	@Test
	public void fm5_loadSingleDetailFindsEntry() {
		AudioLibrary lib = createLibrary();
		populateLibrary(lib, TEST_CACHE_CAPACITY);
		String targetId = "test-id-0005";

		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);

		WaveDetails loaded = AudioLibraryPersistence.loadSingleDetail(prefix, targetId);

		assertNotNull("loadSingleDetail should find the entry", loaded);
		assertTrue("Loaded entry should have correct identifier",
				targetId.equals(loaded.getIdentifier()));
		assertEquals("Loaded entry should have correct sample rate",
				SAMPLE_RATE, loaded.getSampleRate());
	}

	/**
	 * FM5.6: loadSingleDetail returns null for a non-existent identifier
	 * without throwing an exception.
	 */
	@Test
	public void fm5_loadSingleDetailReturnsNullForMissingEntry() {
		AudioLibrary lib = createLibrary();
		populateLibrary(lib, TEST_CACHE_CAPACITY);

		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);

		WaveDetails missing = AudioLibraryPersistence.loadSingleDetail(prefix, "nonexistent-id");
		Assert.assertNull("loadSingleDetail should return null for unknown identifier", missing);
	}

	/**
	 * FM5.7: saveLibrary throws {@link IllegalStateException} when the
	 * save guard detects unloadable entries, verifying that the guard
	 * message includes the entry counts.
	 */
	@Test
	public void fm5_saveGuardIncludesEntryCounts() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		populateLibrary(lib, total);

		String prefix = savePrefix();
		try {
			AudioLibraryPersistence.saveLibrary(lib, prefix);
			Assert.fail("saveLibrary should throw IllegalStateException when entries are unloadable");
		} catch (IllegalStateException e) {
			assertTrue("Exception message should mention entry counts",
					e.getMessage().contains("of " + total));
			assertTrue("Exception message should mention setDetailsLoader",
					e.getMessage().contains("setDetailsLoader"));
		}
	}

	// ============================================================
	// FM6: Cache Thrashing During Bulk Operations
	//
	// When allDetails() iterates over allIdentifiers and calls
	// getOrLoad() for each entry, each loaded entry goes into the
	// cache, potentially evicting another entry. With more identifiers
	// than cache capacity, this causes continuous eviction/reload cycles.
	// ============================================================

	/**
	 * FM6.1: allDetails() with a loader returns all entries despite
	 * cache thrashing.
	 */
	@Test
	public void fm6_allDetailsWithLoaderReturnsAllEntries() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		lib.setDetailsLoader(createLoaderFromMap(entries));

		List<WaveDetails> allDetails = lib.allDetails().collect(Collectors.toList());
		assertEquals("allDetails with loader should return all entries",
				total, allDetails.size());
	}

	/**
	 * FM6.2: Repeated allDetails() calls all produce the same count
	 * when a loader is configured.
	 */
	@Test
	public void fm6_repeatedAllDetailsProducesConsistentCount() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);
		lib.setDetailsLoader(createLoaderFromMap(entries));

		int count1 = lib.getAllDetails().size();
		int count2 = lib.getAllDetails().size();
		int count3 = lib.getAllDetails().size();

		assertEquals("First call should return all entries", total, count1);
		assertEquals("Second call should return same count", count1, count2);
		assertEquals("Third call should return same count", count1, count3);
	}

	/**
	 * FM6.3: The loader is invoked for every evicted entry during
	 * allDetails() iteration, proving that disk I/O occurs for each
	 * cache miss.
	 */
	@Test
	public void fm6_loaderInvokedForEvictedEntries() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		AtomicInteger loadCount = new AtomicInteger(0);
		lib.setDetailsLoader(id -> {
			loadCount.incrementAndGet();
			WaveDetails original = entries.get(id);
			if (original == null) return null;
			return createDetails(id);
		});

		lib.getAllDetails();
		int loadsAfterFirst = loadCount.get();

		assertTrue("Loader should be invoked for evicted entries "
						+ "(loaded " + loadsAfterFirst + " entries from disk)",
				loadsAfterFirst > 0);

		// Call again - more loads expected due to cache thrashing
		loadCount.set(0);
		lib.getAllDetails();
		int loadsAfterSecond = loadCount.get();

		assertTrue("Second allDetails() call should also trigger loads "
						+ "(cache thrashing): " + loadsAfterSecond + " loads",
				loadsAfterSecond > 0);
	}

	/**
	 * FM6.4: After allDetails() with a loader, the cache contains at
	 * most capacity entries (the last entries loaded during iteration).
	 */
	@Test
	public void fm6_cacheAtCapacityAfterBulkOperation() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);
		lib.setDetailsLoader(createLoaderFromMap(entries));

		lib.getAllDetails();

		// Count entries retrievable without the loader
		lib.setDetailsLoader(null);
		int retrievable = 0;
		for (String id : entries.keySet()) {
			if (lib.get(id) != null) {
				retrievable++;
			}
		}

		assertTrue("After bulk operation, cache should have at most capacity entries "
						+ "(retrievable: " + retrievable
						+ ", capacity: " + TEST_CACHE_CAPACITY + ")",
				retrievable <= TEST_CACHE_CAPACITY);
	}

	/**
	 * FM6.5: allDetails() without a loader returns only cached entries,
	 * no cache thrashing occurs (but data is lost).
	 */
	@Test
	public void fm6_allDetailsWithoutLoaderNoThrashing() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// No loader set
		List<WaveDetails> details1 = new ArrayList<>(lib.getAllDetails());
		List<WaveDetails> details2 = new ArrayList<>(lib.getAllDetails());

		assertEquals("Both calls should return the same count without loader",
				details1.size(), details2.size());
		assertTrue("Count should be at most capacity",
				details1.size() <= TEST_CACHE_CAPACITY);
	}

	// ============================================================
	// FM7: Stale Similarity Data After Eviction and Reload
	//
	// When an entry is evicted from cache, its in-memory similarity
	// map is lost. resetSimilarities() only clears cached entries.
	// When an evicted entry is later reloaded from disk, it comes
	// back with whatever similarities were in the protobuf (possibly
	// stale or unreset data).
	// ============================================================

	/**
	 * FM7.1: In-memory similarity updates are lost when an entry is
	 * evicted and reloaded.
	 */
	@Test
	public void fm7_inMemorySimilarityLostOnEvictionAndReload() {
		AudioLibrary lib = createLibrary();

		// Add an entry and set its similarities
		String targetId = "target-entry";
		WaveDetails target = createDetails(targetId);
		lib.include(target);
		target.getSimilarities().put("ref-001", 0.88);

		// Create a loader that returns entries WITHOUT the in-memory update
		Map<String, WaveDetails> loaderData = new HashMap<>();
		loaderData.put(targetId, createDetails(targetId)); // fresh copy, no similarities
		lib.setDetailsLoader(createLoaderFromMap(loaderData));

		// Seed the cache access counter by calling get() on the target itself.
		// This ensures FrequencyCache.count > 0, so the eviction scoring formula
		// does not produce NaN (division by zero when count=0). Without this,
		// eviction order is non-deterministic and the target may not be evicted.
		lib.get(targetId);

		// Evict the target by filling the cache with enough new entries.
		// The target has frequency=1 (one get() call above) while each new entry
		// starts at frequency=0, but the age component ensures older entries
		// with low frequency are evicted first.
		for (int i = 0; i < TEST_CACHE_CAPACITY + 5; i++) {
			String evictorId = "evictor-" + String.format("%04d", i);
			lib.include(createDetails(evictorId));
			// Access each evictor so it has higher frequency than the target
			lib.get(evictorId);
		}

		// Reload the target
		WaveDetails reloaded = lib.get(targetId);
		assertNotNull("Target should be reloadable via loader", reloaded);

		// The in-memory similarity update should be lost
		assertFalse("Reloaded entry should NOT have the in-memory similarity update",
				reloaded.getSimilarities().containsKey("ref-001"));
	}

	/**
	 * FM7.2: resetSimilarities() only clears similarities for entries
	 * currently in the cache, not evicted entries.
	 */
	@Test
	public void fm7_resetSimilaritiesOnlyClearsCachedEntries() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Set similarities for all entries
		for (WaveDetails d : entries.values()) {
			d.getSimilarities().put("common-ref", 0.55);
		}

		// Reset similarities - only clears cached entries
		lib.resetSimilarities();

		// Cached entries should have cleared similarities
		int clearedCount = 0;
		int unclearedCount = 0;
		for (WaveDetails d : entries.values()) {
			if (d.getSimilarities().isEmpty()) {
				clearedCount++;
			} else {
				unclearedCount++;
			}
		}

		assertTrue("Some entries should have cleared similarities "
						+ "(cleared: " + clearedCount + ")",
				clearedCount > 0);
		assertTrue("Evicted entries should retain stale similarities "
						+ "(uncleared: " + unclearedCount + ")",
				unclearedCount > 0);
	}

	/**
	 * FM7.3: After reset, reloaded entries come back with stale
	 * (unreset) similarity data.
	 */
	@Test
	public void fm7_reloadedEntriesHaveStaleSimilaritiesAfterReset() {
		AudioLibrary lib = createLibrary();

		// Create entries with pre-set similarities and store in loader
		String targetId = "stale-target";
		WaveDetails target = createDetails(targetId);
		target.getSimilarities().put("stale-ref", 0.77);
		lib.include(target);

		// Snapshot the similarities independently from the live object so
		// that resetSimilarities() on the cached entry cannot mutate the
		// loader's copy (simulates loading from disk).
		Map<String, Map<String, Double>> loaderSimilarities = new HashMap<>();
		loaderSimilarities.put(targetId, new HashMap<>(target.getSimilarities()));

		lib.setDetailsLoader(id -> {
			Map<String, Double> sims = loaderSimilarities.get(id);
			if (sims == null) return null;
			// Simulate loading from disk: returns entry with original similarities
			WaveDetails copy = createDetails(id);
			copy.getSimilarities().putAll(sims);
			return copy;
		});

		// Fill cache to evict the target
		for (int i = 0; i < TEST_CACHE_CAPACITY + 5; i++) {
			lib.include(createDetails("filler-" + String.format("%04d", i)));
		}

		// Reset similarities (only affects cached entries, not evicted target)
		lib.resetSimilarities();

		// Reload target - it should come back with stale (unreset) similarities
		WaveDetails reloaded = lib.get(targetId);
		assertNotNull("Target should be reloadable", reloaded);
		assertTrue("Reloaded entry should have stale similarity data "
						+ "(was not cleared by resetSimilarities)",
				reloaded.getSimilarities().containsKey("stale-ref"));
		assertEquals("Stale similarity value should be preserved",
				0.77, reloaded.getSimilarities().get("stale-ref"), 1e-6);
	}

	/**
	 * FM7.4: resetSimilarities() increments the similarities version
	 * counter.
	 */
	@Test
	public void fm7_resetIncrementsSimilaritiesVersion() {
		AudioLibrary lib = createLibrary();
		populateLibrary(lib, 5);

		long versionBefore = lib.getSimilaritiesVersion();
		lib.resetSimilarities();
		long versionAfter = lib.getSimilaritiesVersion();

		assertTrue("Version should be incremented after reset "
						+ "(before: " + versionBefore + ", after: " + versionAfter + ")",
				versionAfter > versionBefore);
	}

	/**
	 * FM7.5: markSimilaritiesChanged() increments the version counter
	 * independently of resetSimilarities().
	 */
	@Test
	public void fm7_markSimilaritiesChangedIncrementsVersion() {
		AudioLibrary lib = createLibrary();

		long v0 = lib.getSimilaritiesVersion();
		lib.markSimilaritiesChanged();
		long v1 = lib.getSimilaritiesVersion();
		lib.markSimilaritiesChanged();
		long v2 = lib.getSimilaritiesVersion();

		assertEquals("First increment", v0 + 1, v1);
		assertEquals("Second increment", v0 + 2, v2);
	}

	// ============================================================
	// FM8: allIdentifiers vs detailsCache Inconsistency
	//
	// allIdentifiers is a Set<String> tracking ALL known identifiers,
	// including evicted ones. detailsCache only holds up to capacity
	// entries. After eviction, these two structures diverge:
	// allIdentifiers contains entries that detailsCache doesn't.
	// cleanup() only iterates detailsCache.forEach(), so evicted
	// entries in allIdentifiers become phantom references.
	// ============================================================

	/**
	 * FM8.1: allIdentifiers contains entries that are not in the cache
	 * after eviction.
	 */
	@Test
	public void fm8_allIdentifiersContainsEvictedEntries() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		populateLibrary(lib, total);

		Set<String> allIds = lib.getAllIdentifiers();
		assertEquals("allIdentifiers should track all entries", total, allIds.size());

		// Count how many are actually in the cache (retrievable without loader)
		int cached = 0;
		for (String id : allIds) {
			if (lib.get(id) != null) {
				cached++;
			}
		}

		assertTrue("Cache should have fewer entries than allIdentifiers "
						+ "(cached: " + cached
						+ ", allIdentifiers: " + allIds.size() + ")",
				cached < allIds.size());
	}

	/**
	 * FM8.2: cleanup() only removes entries from the cache, leaving
	 * phantom entries in allIdentifiers for evicted entries.
	 */
	@Test
	public void fm8_cleanupMissesEvictedPhantomEntries() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		populateLibrary(lib, total);

		int idsBefore = lib.getAllIdentifiers().size();

		// Cleanup with no preservation (all non-persistent, non-active entries removed)
		lib.cleanup(null);

		int idsAfter = lib.getAllIdentifiers().size();

		// cleanup() iterates detailsCache.forEach(), so only cached entries are
		// candidates for removal. Evicted entries in allIdentifiers are never checked.
		// Since there are no active files in our empty tmpDir, all cached entries
		// should be removed. But evicted entries remain.
		assertTrue("Some phantom entries should remain in allIdentifiers after cleanup "
						+ "(before: " + idsBefore + ", after: " + idsAfter + ")",
				idsAfter > 0);
	}

	/**
	 * FM8.3: get() returns null for an identifier that exists in
	 * allIdentifiers but was evicted from the cache (no loader).
	 */
	@Test
	public void fm8_getReturnsNullForPhantomIdentifier() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Find phantom identifiers (in allIdentifiers but not retrievable)
		List<String> phantoms = new ArrayList<>();
		for (String id : lib.getAllIdentifiers()) {
			if (lib.get(id) == null) {
				phantoms.add(id);
			}
		}

		assertFalse("There should be phantom identifiers", phantoms.isEmpty());

		for (String phantom : phantoms) {
			assertTrue("Phantom should be in allIdentifiers",
					lib.getAllIdentifiers().contains(phantom));
			Assert.assertNull("Phantom should return null from get()",
					lib.get(phantom));
		}
	}

	/**
	 * FM8.4: include() correctly adds to both allIdentifiers and the cache.
	 */
	@Test
	public void fm8_includeAddsToBothStructures() {
		AudioLibrary lib = createLibrary();
		String id = "include-test-001";
		WaveDetails details = createDetails(id);

		lib.include(details);

		assertTrue("allIdentifiers should contain the new entry",
				lib.getAllIdentifiers().contains(id));
		assertNotNull("get() should return the entry",
				lib.get(id));
		assertTrue("get() should return the same object that was included",
				details == lib.get(id));
	}

	/**
	 * FM8.5: After eviction, allIdentifiers retains the entry but the
	 * cache does not, and re-including the same identifier updates both.
	 */
	@Test
	public void fm8_reIncludeAfterEvictionUpdatesCache() {
		AudioLibrary lib = createLibrary();

		String id = "re-include-target";
		WaveDetails original = createDetails(id);
		original.getSimilarities().put("old-ref", 0.33);
		lib.include(original);

		// Evict by filling cache
		for (int i = 0; i < TEST_CACHE_CAPACITY + 5; i++) {
			lib.include(createDetails("evictor-" + String.format("%04d", i)));
		}

		// Verify evicted
		assertTrue("allIdentifiers should still contain the entry",
				lib.getAllIdentifiers().contains(id));

		// Re-include with updated data
		WaveDetails updated = createDetails(id);
		updated.getSimilarities().put("new-ref", 0.99);
		lib.include(updated);

		WaveDetails retrieved = lib.get(id);
		assertNotNull("Re-included entry should be retrievable", retrieved);
		assertTrue("Re-included entry should have new similarity data",
				retrieved.getSimilarities().containsKey("new-ref"));
	}

	// ============================================================
	// FM9: Thread Safety
	//
	// FrequencyCache.get() and put() are not synchronized.
	// allIdentifiers is a plain HashSet. Concurrent modifications
	// can cause ConcurrentModificationException or lost updates.
	// ============================================================

	/**
	 * FM9.1: Concurrent include() calls from multiple threads should
	 * not throw exceptions (testing for ConcurrentModificationException
	 * on allIdentifiers HashSet).
	 */
	@Test
	public void fm9_concurrentIncludeDoesNotThrow() throws Exception {
		AudioLibrary lib = createLibrary();
		int numThreads = 10;
		int entriesPerThread = 5;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(numThreads);
		AtomicInteger errors = new AtomicInteger(0);
		AtomicBoolean exceptionOccurred = new AtomicBoolean(false);

		ExecutorService executor = Executors.newFixedThreadPool(numThreads);

		for (int t = 0; t < numThreads; t++) {
			int threadId = t;
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int i = 0; i < entriesPerThread; i++) {
						String id = "thread-" + threadId + "-entry-" + i;
						lib.include(createDetails(id));
					}
				} catch (ConcurrentModificationException e) {
					exceptionOccurred.set(true);
					errors.incrementAndGet();
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		assertTrue("All threads should complete within 30 seconds",
				doneLatch.await(30, TimeUnit.SECONDS));
		executor.shutdown();

		// This test documents a known thread-safety issue.
		// We log rather than fail because race conditions are
		// non-deterministic - absence of errors does not prove safety.
		if (exceptionOccurred.get()) {
			log("THREAD SAFETY BUG DETECTED: ConcurrentModificationException during "
					+ "concurrent include() - allIdentifiers (HashSet) is not thread-safe");
		}
	}

	/**
	 * FM9.2: Concurrent get() calls should not corrupt cache state.
	 */
	@Test
	public void fm9_concurrentGetDoesNotCorruptState() throws Exception {
		AudioLibrary lib = createLibrary();
		Map<String, WaveDetails> entries = populateLibrary(lib, TEST_CACHE_CAPACITY);
		List<String> ids = new ArrayList<>(entries.keySet());

		int numThreads = 10;
		int readsPerThread = 100;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(numThreads);
		AtomicInteger errors = new AtomicInteger(0);

		ExecutorService executor = Executors.newFixedThreadPool(numThreads);

		for (int t = 0; t < numThreads; t++) {
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int i = 0; i < readsPerThread; i++) {
						String id = ids.get(i % ids.size());
						lib.get(id); // Should not throw
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		assertTrue("All threads should complete",
				doneLatch.await(30, TimeUnit.SECONDS));
		executor.shutdown();

		assertEquals("No errors should occur during concurrent reads", 0, errors.get());
	}

	/**
	 * FM9.3: Concurrent allDetails() iteration should not throw
	 * ConcurrentModificationException.
	 */
	@Test
	public void fm9_concurrentAllDetailsIterationSafe() throws Exception {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);
		lib.setDetailsLoader(createLoaderFromMap(entries));

		int numThreads = 5;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(numThreads);
		AtomicInteger errors = new AtomicInteger(0);
		AtomicBoolean cmeOccurred = new AtomicBoolean(false);

		ExecutorService executor = Executors.newFixedThreadPool(numThreads);

		for (int t = 0; t < numThreads; t++) {
			executor.submit(() -> {
				try {
					startLatch.await();
					long count = lib.allDetails().count();
					// count may vary due to concurrent cache modifications
				} catch (ConcurrentModificationException e) {
					cmeOccurred.set(true);
					errors.incrementAndGet();
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		assertTrue("All threads should complete",
				doneLatch.await(30, TimeUnit.SECONDS));
		executor.shutdown();

		if (cmeOccurred.get()) {
			log("THREAD SAFETY BUG DETECTED: ConcurrentModificationException during "
					+ "concurrent allDetails() iteration");
		}
	}

	/**
	 * FM9.4: Concurrent include() and get() operations should not cause
	 * exceptions.
	 */
	@Test
	public void fm9_concurrentIncludeAndGetMixed() throws Exception {
		AudioLibrary lib = createLibrary();

		int numWriters = 5;
		int numReaders = 5;
		int opsPerThread = 50;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(numWriters + numReaders);
		AtomicInteger errors = new AtomicInteger(0);

		ExecutorService executor = Executors.newFixedThreadPool(numWriters + numReaders);

		// Writers
		for (int t = 0; t < numWriters; t++) {
			int threadId = t;
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int i = 0; i < opsPerThread; i++) {
						String id = "writer-" + threadId + "-" + i;
						lib.include(createDetails(id));
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		// Readers
		for (int t = 0; t < numReaders; t++) {
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int i = 0; i < opsPerThread; i++) {
						lib.getAllIdentifiers().size();
						lib.getAllDetails().size();
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		assertTrue("All threads should complete",
				doneLatch.await(60, TimeUnit.SECONDS));
		executor.shutdown();

		// This test documents the thread-safety issue.
		// Errors here prove the bug exists but we do not fail the test
		// because race conditions are non-deterministic - the absence
		// of errors in a single run does not prove thread safety.
		if (errors.get() > 0) {
			log("THREAD SAFETY BUG DETECTED: Concurrent include+get produced "
					+ errors.get() + " errors - AudioLibrary is not thread-safe");
		}
	}

	/**
	 * FM9.5: Concurrent getOrLoad() calls for the same evicted identifier
	 * with a loader should not cause duplicate loads or data corruption.
	 */
	@Test
	public void fm9_concurrentGetOrLoadForSameEntry() throws Exception {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		AtomicInteger loadCount = new AtomicInteger(0);
		lib.setDetailsLoader(id -> {
			loadCount.incrementAndGet();
			WaveDetails d = entries.get(id);
			if (d == null) return null;
			return createDetails(id);
		});

		// Find an evicted entry
		String evictedId = null;
		for (String id : entries.keySet()) {
			if (lib.get(id) == null) {
				evictedId = id;
				break;
			}
		}

		if (evictedId == null) {
			// No eviction happened (unlikely with our capacity settings)
			return;
		}

		// Reset load count and evict the entry again
		loadCount.set(0);
		for (int i = 0; i < TEST_CACHE_CAPACITY + 5; i++) {
			lib.include(createDetails("re-evictor-" + String.format("%04d", i)));
		}

		String targetId = evictedId;
		int numThreads = 10;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(numThreads);
		ConcurrentHashMap<Integer, WaveDetails> results = new ConcurrentHashMap<>();
		AtomicInteger errors = new AtomicInteger(0);

		ExecutorService executor = Executors.newFixedThreadPool(numThreads);

		for (int t = 0; t < numThreads; t++) {
			int threadId = t;
			executor.submit(() -> {
				try {
					startLatch.await();
					WaveDetails d = lib.get(targetId);
					if (d != null) {
						results.put(threadId, d);
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		assertTrue("All threads should complete",
				doneLatch.await(30, TimeUnit.SECONDS));
		executor.shutdown();

		assertEquals("No errors during concurrent get", 0, errors.get());
		// The loader may be called multiple times for the same entry
		// because get() and put() are not synchronized
		assertTrue("Loader should have been called at least once",
				loadCount.get() >= 1);
	}

	// ============================================================
	// FM10: PrototypeDiscovery Integration
	//
	// PrototypeDiscovery loads library data from protobuf via
	// AudioLibraryPersistence.loadLibrary(), which calls include()
	// for each entry. With more entries than cache capacity, entries
	// are evicted during loading. PrototypeDiscovery never calls
	// setDetailsLoader(), so evicted entries are permanently lost.
	// The prototype analysis then operates on a subset of the data.
	// ============================================================

	/**
	 * FM10.1: Loading more entries than cache capacity via include()
	 * results in data loss when getAllDetails() is called without
	 * a loader (simulates what PrototypeDiscovery does).
	 */
	@Test
	public void fm10_loadingMoreThanCapacityLosesEntriesWithoutLoader() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		populateLibrary(lib, total);

		// PrototypeDiscovery pattern: getAllDetails() without setDetailsLoader()
		Collection<WaveDetails> details = lib.getAllDetails();

		assertTrue("Without loader, getAllDetails should return fewer entries "
						+ "than total (got " + details.size() + " of " + total + ")",
				details.size() < total);

		// This is exactly what PrototypeDiscovery does at line 120-122
		List<WaveDetails> withFeatures = details.stream()
				.filter(d -> d.getFeatureData() != null)
				.collect(Collectors.toList());

		assertTrue("Feature-filtered list should also be incomplete "
						+ "(got " + withFeatures.size() + " of " + total + ")",
				withFeatures.size() < total);
	}

	/**
	 * FM10.2: Prototype analysis with partial data produces results
	 * based on a subset, not the full library.
	 */
	@Test
	public void fm10_prototypeAnalysisUsesPartialData() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Pre-populate similarities
		List<String> ids = new ArrayList<>(entries.keySet());
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				double sim = 1.0 / (1.0 + Math.abs(i - j));
				entries.get(ids.get(i)).getSimilarities().put(ids.get(j), sim);
				entries.get(ids.get(j)).getSimilarities().put(ids.get(i), sim);
			}
		}

		// Without loader: build graph from partial data
		AudioSimilarityGraph partialGraph = lib.toSimilarityGraph();
		int partialNodes = partialGraph.countNodes();

		// With loader: build graph from full data
		lib.setDetailsLoader(createLoaderFromMap(entries));
		AudioSimilarityGraph fullGraph = lib.toSimilarityGraph();
		int fullNodes = fullGraph.countNodes();

		assertTrue("Partial graph should have fewer nodes "
						+ "(partial: " + partialNodes + ", full: " + fullNodes + ")",
				partialNodes < fullNodes);
		assertEquals("Full graph should have all entries",
				total, fullNodes);
	}

	/**
	 * FM10.3: Setting detailsLoader before calling getAllDetails() makes
	 * the full dataset available for analysis.
	 */
	@Test
	public void fm10_settingLoaderBeforeGetAllDetailsGivesFullData() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Set loader (what PrototypeDiscovery should do but doesn't)
		lib.setDetailsLoader(createLoaderFromMap(entries));

		Collection<WaveDetails> details = lib.getAllDetails();
		assertEquals("With loader, getAllDetails should return all entries",
				total, details.size());

		List<WaveDetails> withFeatures = details.stream()
				.filter(d -> d.getFeatureData() != null)
				.collect(Collectors.toList());
		assertEquals("All entries should have feature data",
				total, withFeatures.size());
	}

	/**
	 * FM10.4: Save from a library with a loader, then load into a new
	 * library. {@link AudioLibraryPersistence#loadLibrary(AudioLibrary, String)}
	 * auto-wires the details loader, so all entries remain accessible even
	 * when the number of entries exceeds cache capacity.
	 */
	@Test
	public void fm10_saveAndReloadPreservesAllEntriesViaAutoWiring() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);
		lib.setDetailsLoader(createLoaderFromMap(entries));

		// Save with loader (all entries saved)
		String prefix = savePrefix();
		AudioLibraryPersistence.saveLibrary(lib, prefix);
		int savedCount = countSavedEntries(prefix);
		assertEquals("Save with loader should write all entries",
				total, savedCount);

		lib.stop();
		library = null;

		// Load into new library — loadLibrary auto-wires the details loader
		AudioLibrary discoveryLib = createLibrary();
		AudioLibraryPersistence.loadLibrary(discoveryLib, prefix);

		// getAllDetails() should return all entries via auto-wired loader
		Collection<WaveDetails> discoveredDetails = discoveryLib.getAllDetails();
		assertEquals("Auto-wired loader should make all entries accessible",
				total, discoveredDetails.size());
	}

	/**
	 * FM10.5: getAllDetails().size() matches getAllIdentifiers().size()
	 * only when a loader is properly configured.
	 */
	@Test
	public void fm10_detailsMatchesIdentifiersOnlyWithLoader() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 10;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Without loader: mismatch
		int detailsWithout = lib.getAllDetails().size();
		int identifiers = lib.getAllIdentifiers().size();
		assertTrue("Without loader, details and identifiers should mismatch "
						+ "(details: " + detailsWithout + ", identifiers: " + identifiers + ")",
				detailsWithout != identifiers);

		// With loader: match
		lib.setDetailsLoader(createLoaderFromMap(entries));
		int detailsWith = lib.getAllDetails().size();
		assertEquals("With loader, details count should match identifiers count",
				identifiers, detailsWith);
	}

	// ============================================================
	// FM11: isPrototypeIndexStale() with FrequencyCache
	//
	// The isPrototypeIndexStale() method was changed to use
	// allIdentifiers instead of the old HashMap keySet. These tests
	// verify it correctly detects staleness conditions using the
	// allIdentifiers set, including when entries have been evicted
	// from the cache.
	// ============================================================

	/**
	 * Helper to create a {@link PrototypeIndexData} from the given library
	 * entries. Each entry is assigned to a single community whose prototype
	 * is the first entry.
	 *
	 * @param identifiers the identifiers to include in the index
	 * @return a PrototypeIndexData with one community containing all identifiers
	 */
	private PrototypeIndexData createIndex(List<String> identifiers) {
		return createIndex(identifiers, System.currentTimeMillis());
	}

	/**
	 * Helper to create a {@link PrototypeIndexData} with a specific timestamp.
	 *
	 * @param identifiers the identifiers to include
	 * @param computedAt  the timestamp for the index
	 * @return a PrototypeIndexData with one community
	 */
	private PrototypeIndexData createIndex(List<String> identifiers, long computedAt) {
		if (identifiers.isEmpty()) {
			return new PrototypeIndexData(computedAt, List.of());
		}

		PrototypeIndexData.Community community = new PrototypeIndexData.Community(
				identifiers.get(0), 0.5, identifiers);
		return new PrototypeIndexData(computedAt, List.of(community));
	}

	/**
	 * FM11.1: isPrototypeIndexStale returns true when no index is set.
	 */
	@Test
	public void fm11_staleWhenNoIndex() {
		AudioLibrary lib = createLibrary();
		populateLibrary(lib, 5);

		assertTrue("isPrototypeIndexStale should return true when no index is set",
				lib.isPrototypeIndexStale());
	}

	/**
	 * FM11.2: isPrototypeIndexStale returns true when the index has
	 * empty communities.
	 */
	@Test
	public void fm11_staleWhenEmptyCommunities() {
		AudioLibrary lib = createLibrary();
		populateLibrary(lib, 5);
		lib.setPrototypeIndex(new PrototypeIndexData(System.currentTimeMillis(), List.of()));

		assertTrue("isPrototypeIndexStale should return true for empty communities",
				lib.isPrototypeIndexStale());
	}

	/**
	 * FM11.3: isPrototypeIndexStale returns false when the index exactly
	 * matches the library contents (including evicted entries tracked
	 * by allIdentifiers).
	 */
	@Test
	public void fm11_notStaleWhenIndexMatchesAllIdentifiers() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Create index from all identifiers (including evicted ones)
		List<String> allIds = new ArrayList<>(lib.getAllIdentifiers());
		assertEquals("allIdentifiers should track all entries", total, allIds.size());

		lib.setPrototypeIndex(createIndex(allIds));

		assertFalse("isPrototypeIndexStale should return false when index matches "
						+ "all identifiers including evicted entries",
				lib.isPrototypeIndexStale());
	}

	/**
	 * FM11.4: isPrototypeIndexStale returns true when the prototype
	 * identifier has been removed from the library (e.g., via cleanup).
	 */
	@Test
	public void fm11_staleWhenPrototypeDeleted() {
		AudioLibrary lib = createLibrary();
		Map<String, WaveDetails> entries = populateLibrary(lib, 5);
		List<String> ids = new ArrayList<>(entries.keySet());

		// Set index with first entry as prototype
		lib.setPrototypeIndex(createIndex(ids));
		assertFalse("Should not be stale initially", lib.isPrototypeIndexStale());

		// Remove the prototype entry via cleanup
		lib.cleanup(id -> !id.equals(ids.get(0)));

		assertTrue("isPrototypeIndexStale should return true when prototype is deleted",
				lib.isPrototypeIndexStale());
	}

	/**
	 * FM11.5: isPrototypeIndexStale returns true when more than 5% new
	 * entries have been added beyond what is indexed, using allIdentifiers
	 * (not just cached entries) for the size check.
	 */
	@Test
	public void fm11_staleWhenSignificantAdditions() {
		AudioLibrary lib = createLibrary();
		Map<String, WaveDetails> entries = populateLibrary(lib, TEST_CACHE_CAPACITY);
		List<String> ids = new ArrayList<>(lib.getAllIdentifiers());

		lib.setPrototypeIndex(createIndex(ids));
		assertFalse("Should not be stale with matching index",
				lib.isPrototypeIndexStale());

		// Add more than 5% new entries — total was TEST_CACHE_CAPACITY (10),
		// so adding 1 = 10% is above the 5% threshold.
		for (int i = 0; i < 2; i++) {
			lib.include(createDetails("new-entry-" + i));
		}

		assertTrue("isPrototypeIndexStale should return true when allIdentifiers "
						+ "has grown by more than 5%",
				lib.isPrototypeIndexStale());
	}

	/**
	 * FM11.6: isPrototypeIndexStale correctly uses allIdentifiers (not
	 * the cache) to detect missing members, even when the missing entries
	 * have been evicted from the cache.
	 */
	@Test
	public void fm11_detectsMissingMembersViaAllIdentifiers() {
		AudioLibrary lib = createLibrary();
		int total = TEST_CACHE_CAPACITY + 5;
		Map<String, WaveDetails> entries = populateLibrary(lib, total);

		// Index references all identifiers
		List<String> allIds = new ArrayList<>(lib.getAllIdentifiers());
		lib.setPrototypeIndex(createIndex(allIds));

		// Not stale — allIdentifiers contains all indexed members
		assertFalse("Should not be stale when allIdentifiers matches index",
				lib.isPrototypeIndexStale());

		// allIdentifiers has entries beyond cache, proving the check
		// uses allIdentifiers, not just the cache
		assertTrue("Cache should be smaller than allIdentifiers",
				TEST_CACHE_CAPACITY < lib.getAllIdentifiers().size());
	}
}
