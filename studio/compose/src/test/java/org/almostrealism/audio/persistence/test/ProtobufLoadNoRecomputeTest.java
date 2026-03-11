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
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.persistence.AudioLibraryPersistence;
import org.almostrealism.audio.persistence.LibraryDestination;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Verifies that loading an {@link AudioLibrary} from a protobuf file does NOT
 * trigger feature recomputation for entries whose data is already complete.
 *
 * <p>Each test generates real {@code .wav} audio files on disk, creates
 * {@link WaveDetails} with complete frequency and feature data, persists
 * them via {@link AudioLibraryPersistence#saveLibrary}, reloads via
 * {@link AudioLibraryPersistence#loadLibrary}, and asserts that zero
 * calls to {@code computeDetails} (the feature recomputation path) occur
 * during subsequent operations.</p>
 *
 * <p>Recomputation detection is implemented by subclassing {@link AudioLibrary}
 * and overriding the {@code computeDetails(WaveDataProvider, boolean)} method
 * with an {@link AtomicInteger} counter. Any increment of this counter after
 * a protobuf load indicates an unwanted recomputation.</p>
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>getAllDetails after load</li>
 *   <li>computeSimilarities after load</li>
 *   <li>get without detailsLoader</li>
 *   <li>cache eviction and reload via loader</li>
 *   <li>refresh with matching audio files</li>
 *   <li>resetSimilarities then computeSimilarities</li>
 *   <li>iterate all identifiers via get</li>
 *   <li>1000-entry library with subset similarity</li>
 *   <li>double round-trip (save-load-save-load)</li>
 *   <li>full app startup sequence</li>
 * </ol>
 *
 * @see AudioLibrary
 * @see AudioLibraryPersistence
 * @see WaveDetails
 */
public class ProtobufLoadNoRecomputeTest extends TestSuiteBase {

	/** Sample rate used for all test audio and details. */
	private static final int SAMPLE_RATE = 44100;

	/** Number of stereo frames per generated audio file (0.05 seconds). */
	private static final int AUDIO_FRAMES = 2205;

	/** Number of frequency bins in synthetic frequency data. */
	private static final int FREQ_BINS = 32;

	/** Number of frequency frames in synthetic frequency data. */
	private static final int FREQ_FRAMES = 10;

	/** Number of feature bins in synthetic feature data. */
	private static final int FEATURE_BINS = 16;

	/** Number of feature frames in synthetic feature data. */
	private static final int FEATURE_FRAMES = 8;

	private int originalCapacity;
	private File audioDir;
	private File dataDir;
	private List<AudioLibrary> activeLibraries;

	@Before
	public void setUp() throws Exception {
		originalCapacity = AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY;
		audioDir = Files.createTempDirectory("protobuf-nocomp-audio").toFile();
		dataDir = Files.createTempDirectory("protobuf-nocomp-data").toFile();
		activeLibraries = new ArrayList<>();
	}

	@After
	public void tearDown() {
		AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = originalCapacity;
		for (AudioLibrary lib : activeLibraries) {
			lib.stop();
		}
		activeLibraries.clear();
		deleteRecursive(audioDir);
		deleteRecursive(dataDir);
	}

	// ================================================================
	// Instrumented AudioLibrary
	// ================================================================

	/**
	 * Subclass of {@link AudioLibrary} that counts calls to
	 * {@code computeDetails(WaveDataProvider, boolean)}. Any call
	 * after a protobuf load indicates unwanted feature recomputation.
	 */
	private static class InstrumentedAudioLibrary extends AudioLibrary {
		private final AtomicInteger recomputeCount = new AtomicInteger(0);

		InstrumentedAudioLibrary(File root, int sampleRate) {
			super(root, sampleRate);
		}

		@Override
		protected WaveDetails computeDetails(WaveDataProvider provider, boolean persistent) {
			recomputeCount.incrementAndGet();
			return super.computeDetails(provider, persistent);
		}

		/** Returns the number of times computeDetails was invoked. */
		int getRecomputeCount() {
			return recomputeCount.get();
		}
	}

	// ================================================================
	// Helper Methods
	// ================================================================

	/**
	 * Generates a stereo sine wave {@code .wav} file in the given directory.
	 *
	 * @param dir       directory to create the file in
	 * @param index     numeric index for the filename
	 * @param frequency sine wave frequency in Hz
	 * @return the created file
	 */
	private File generateAudioFile(File dir, int index, double frequency) {
		PackedCollection data = new PackedCollection(2, AUDIO_FRAMES);
		for (int i = 0; i < AUDIO_FRAMES; i++) {
			double t = (double) i / SAMPLE_RATE;
			double value = 0.5 * Math.sin(2 * Math.PI * frequency * t);
			data.setMem(i, value);
			data.setMem(AUDIO_FRAMES + i, value);
		}

		WaveData waveData = new WaveData(data, SAMPLE_RATE);
		File file = new File(dir, "audio_" + index + ".wav");
		assertTrue("Failed to save audio file: " + file, waveData.save(file));
		return file;
	}

	/**
	 * Computes the MD5 content identifier for a file, matching the
	 * algorithm used by {@link org.almostrealism.audio.data.FileWaveDataProvider}.
	 */
	private String computeIdentifier(File file) {
		try (InputStream is = Files.newInputStream(file.toPath())) {
			return DigestUtils.md5Hex(is);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Creates a {@link WaveDetails} with complete metadata, frequency data,
	 * and feature data. The data values are deterministic based on the
	 * identifier for verification after protobuf round-trips.
	 */
	private WaveDetails createCompleteDetails(String identifier) {
		WaveDetails details = new WaveDetails(identifier);
		details.setSampleRate(SAMPLE_RATE);
		details.setChannelCount(1);
		details.setFrameCount(AUDIO_FRAMES);

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
	 * Generates {@code count} real audio files in the given directory and
	 * creates matching {@link WaveDetails} with complete data for each.
	 */
	private List<WaveDetails> generateAndPrepare(File dir, int count) {
		List<WaveDetails> detailsList = new ArrayList<>();
		Map<String, Integer> seenIdentifiers = new java.util.HashMap<>();

		for (int i = 0; i < count; i++) {
			double frequency = 220.0 + i * 20.0;
			File file = generateAudioFile(dir, i, frequency);
			String identifier = computeIdentifier(file);

			if (seenIdentifiers.containsKey(identifier)) {
				continue;
			}
			seenIdentifiers.put(identifier, i);

			WaveDetails details = createCompleteDetails(identifier);
			detailsList.add(details);
		}
		return detailsList;
	}

	/**
	 * Populates bidirectional similarity scores between all entries in
	 * the list. Scores are deterministic based on entry index distance.
	 */
	private void populateSimilarities(List<WaveDetails> detailsList) {
		for (int i = 0; i < detailsList.size(); i++) {
			for (int j = i + 1; j < detailsList.size(); j++) {
				double sim = 0.8 - 0.01 * Math.abs(i - j);
				String idI = detailsList.get(i).getIdentifier();
				String idJ = detailsList.get(j).getIdentifier();
				detailsList.get(i).getSimilarities().put(idJ, sim);
				detailsList.get(j).getSimilarities().put(idI, sim);
			}
		}
	}

	/**
	 * Saves the given details to protobuf via a temporary {@link AudioLibrary}.
	 *
	 * @param detailsList entries to save
	 * @param name        filename prefix within the data directory
	 * @return the data prefix path for loading
	 */
	private String saveToProtobuf(List<WaveDetails> detailsList, String name) {
		AudioLibrary saveLib = new AudioLibrary(audioDir, SAMPLE_RATE);
		activeLibraries.add(saveLib);

		for (WaveDetails d : detailsList) {
			saveLib.include(d);
		}

		Map<String, WaveDetails> map = detailsList.stream()
				.collect(Collectors.toMap(WaveDetails::getIdentifier, d -> d, (a, b) -> a));
		saveLib.setDetailsLoader(map::get);

		String prefix = new File(dataDir, name).getAbsolutePath();
		AudioLibraryPersistence.saveLibrary(saveLib, prefix);

		saveLib.stop();
		activeLibraries.remove(saveLib);
		return prefix;
	}

	/**
	 * Loads from protobuf into an {@link InstrumentedAudioLibrary} with
	 * the auto-wired details loader.
	 */
	private InstrumentedAudioLibrary loadInstrumented(String prefix) {
		InstrumentedAudioLibrary lib = new InstrumentedAudioLibrary(audioDir, SAMPLE_RATE);
		activeLibraries.add(lib);
		AudioLibraryPersistence.loadLibrary(lib, prefix);
		return lib;
	}

	/**
	 * Asserts that all entries in the library have complete data (non-null
	 * frequency data AND non-null feature data).
	 */
	private void assertAllComplete(AudioLibrary lib, int expectedCount) {
		Collection<WaveDetails> all = lib.getAllDetails();
		assertEquals("Expected " + expectedCount + " entries after load",
				expectedCount, all.size());

		for (WaveDetails d : all) {
			assertNotNull(
					"FreqData must not be null after protobuf load for " + d.getIdentifier(),
					d.getFreqData());
			assertNotNull(
					"FeatureData must not be null after protobuf load for " + d.getIdentifier(),
					d.getFeatureData());
			assertTrue(
					"Entry must be complete after protobuf load for " + d.getIdentifier(),
					lib.isComplete(d));
		}
	}

	/**
	 * Asserts that zero feature recomputation calls occurred.
	 *
	 * @param lib     the instrumented library to check
	 * @param context description of what operation was performed
	 */
	private void assertNoRecomputation(InstrumentedAudioLibrary lib, String context) {
		assertEquals(
				"Expected 0 recomputation calls after protobuf load (" + context +
						"), but got " + lib.getRecomputeCount(),
				0, lib.getRecomputeCount());
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

	// ================================================================
	// Test 1: getAllDetails after protobuf load
	// ================================================================

	/**
	 * Loads a protobuf-saved library and calls {@code getAllDetails()}.
	 * All entries must have complete data and zero feature recomputation
	 * calls must occur.
	 */
	@Test
	public void test1_getAllDetailsNoRecomputation() {
		List<WaveDetails> entries = generateAndPrepare(audioDir, 20);
		populateSimilarities(entries);
		String prefix = saveToProtobuf(entries, "t1");

		InstrumentedAudioLibrary lib = loadInstrumented(prefix);

		lib.getAllDetails();

		assertAllComplete(lib, 20);
		assertNoRecomputation(lib, "getAllDetails");
	}

	// ================================================================
	// Test 2: computeSimilarities after protobuf load
	// ================================================================

	/**
	 * Loads a protobuf-saved library with pre-computed similarities and
	 * calls {@code computeSimilarities()} on every entry. Since all
	 * similarity pairs are already present, the method should use the
	 * loaded feature data without triggering feature recomputation.
	 */
	@Test
	public void test2_computeSimilaritiesNoRecomputation() {
		List<WaveDetails> entries = generateAndPrepare(audioDir, 20);
		populateSimilarities(entries);
		String prefix = saveToProtobuf(entries, "t2");

		InstrumentedAudioLibrary lib = loadInstrumented(prefix);

		for (WaveDetails d : lib.getAllDetails()) {
			lib.computeSimilarities(d);
		}

		assertAllComplete(lib, 20);
		assertNoRecomputation(lib, "computeSimilarities");
	}

	// ================================================================
	// Test 3: get without detailsLoader
	// ================================================================

	/**
	 * Loads a protobuf-saved library WITHOUT calling
	 * {@code setDetailsLoader()}, then calls {@code get()} on every
	 * identifier. Since all entries fit in the cache, they should be
	 * returned directly with complete data and zero recomputation.
	 */
	@Test
	public void test3_noLoaderGetNoRecomputation() throws Exception {
		AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = 500;
		List<WaveDetails> entries = generateAndPrepare(audioDir, 15);
		populateSimilarities(entries);
		String prefix = saveToProtobuf(entries, "t3");

		InstrumentedAudioLibrary lib = new InstrumentedAudioLibrary(audioDir, SAMPLE_RATE);
		activeLibraries.add(lib);
		AudioLibraryPersistence.loadLibrary(lib, new LibraryDestination(prefix).in());

		for (WaveDetails original : entries) {
			WaveDetails loaded = lib.get(original.getIdentifier());
			assertNotNull(
					"get() must return cached entry for " + original.getIdentifier(),
					loaded);
			assertTrue(
					"Entry must be complete for " + original.getIdentifier(),
					lib.isComplete(loaded));
		}

		assertNoRecomputation(lib, "get without detailsLoader");
	}

	// ================================================================
	// Test 4: cache eviction + reload via loader
	// ================================================================

	/**
	 * Loads a protobuf-saved library into a cache smaller than the number
	 * of entries, forcing eviction. Then accesses every entry via
	 * {@code get()}, which triggers the details loader for evicted entries.
	 * The loader should return complete data from disk, not the recomputer.
	 */
	@Test
	public void test4_evictionReloadNoRecomputation() {
		int cacheCapacity = 10;
		AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = cacheCapacity;
		int entryCount = cacheCapacity + 15;

		List<WaveDetails> entries = generateAndPrepare(audioDir, entryCount);
		populateSimilarities(entries);
		String prefix = saveToProtobuf(entries, "t4");

		InstrumentedAudioLibrary lib = loadInstrumented(prefix);

		for (WaveDetails original : entries) {
			WaveDetails loaded = lib.get(original.getIdentifier());
			assertNotNull(
					"get() must return entry via loader for " + original.getIdentifier(),
					loaded);
			assertTrue(
					"Reloaded entry must be complete for " + original.getIdentifier(),
					lib.isComplete(loaded));
		}

		assertNoRecomputation(lib, "eviction + reload via loader");
	}

	// ================================================================
	// Test 5: refresh with matching audio files
	// ================================================================

	/**
	 * Loads a protobuf-saved library where the audio files on disk match
	 * the protobuf identifiers. Calling {@code refresh()} should find all
	 * entries already complete and skip them, triggering zero recomputation.
	 */
	@Test
	public void test5_refreshNoRecomputation() throws Exception {
		List<WaveDetails> entries = generateAndPrepare(audioDir, 20);
		populateSimilarities(entries);
		String prefix = saveToProtobuf(entries, "t5");

		InstrumentedAudioLibrary lib = loadInstrumented(prefix);

		lib.refresh();
		lib.awaitRefresh().get(60, TimeUnit.SECONDS);

		assertAllComplete(lib, 20);
		assertNoRecomputation(lib, "refresh");
	}

	// ================================================================
	// Test 6: resetSimilarities then computeSimilarities
	// ================================================================

	/**
	 * Loads a protobuf-saved library, resets all similarity scores, then
	 * calls {@code computeSimilarities()} on every entry. Resetting
	 * similarities clears only the similarity maps, NOT the underlying
	 * frequency or feature data. Feature recomputation must not occur.
	 */
	@Test
	public void test6_resetThenComputeSimilaritiesNoRecomputation() {
		List<WaveDetails> entries = generateAndPrepare(audioDir, 15);
		populateSimilarities(entries);
		String prefix = saveToProtobuf(entries, "t6");

		InstrumentedAudioLibrary lib = loadInstrumented(prefix);

		lib.resetSimilarities();

		for (WaveDetails d : lib.getAllDetails()) {
			lib.computeSimilarities(d);
		}

		assertAllComplete(lib, 15);
		assertNoRecomputation(lib, "resetSimilarities + computeSimilarities");
	}

	// ================================================================
	// Test 7: iterate all identifiers via get
	// ================================================================

	/**
	 * Loads a protobuf-saved library, retrieves all known identifiers, and
	 * calls {@code get()} on each one individually. Every entry must be
	 * returned with complete data and zero recomputation.
	 */
	@Test
	public void test7_iterateGetAllNoRecomputation() {
		List<WaveDetails> entries = generateAndPrepare(audioDir, 20);
		populateSimilarities(entries);
		String prefix = saveToProtobuf(entries, "t7");

		InstrumentedAudioLibrary lib = loadInstrumented(prefix);

		Set<String> identifiers = lib.getAllIdentifiers();
		assertEquals("Should have 20 identifiers", 20, identifiers.size());

		for (String id : identifiers) {
			WaveDetails d = lib.get(id);
			assertNotNull("get() must return entry for identifier " + id, d);
			assertNotNull("FeatureData must be present for " + id, d.getFeatureData());
			assertNotNull("FreqData must be present for " + id, d.getFreqData());
		}

		assertNoRecomputation(lib, "iterate get all identifiers");
	}

	// ================================================================
	// Test 8: 1000-entry library
	// ================================================================

	/**
	 * Generates 1000 real audio files, creates complete {@link WaveDetails}
	 * for each, saves to protobuf, loads, and calls
	 * {@code computeSimilarities()} on a 10-entry subset. All 1000 entries
	 * must load with complete data and zero feature recomputation.
	 */
	@Test
	@TestDepth(1)
	public void test8_largeLibraryNoRecomputation() {
		int fileCount = 1000;

		List<WaveDetails> entries = generateAndPrepare(audioDir, fileCount);
		int entryCount = entries.size();
		assertTrue("Should have at least 900 unique entries", entryCount >= 900);

		AudioLibrary.DEFAULT_DETAIL_CACHE_CAPACITY = entryCount + 100;

		String prefix = saveToProtobuf(entries, "t8");

		InstrumentedAudioLibrary lib = loadInstrumented(prefix);

		Collection<WaveDetails> loaded = lib.getAllDetails();
		assertEquals("Should load all entries", entryCount, loaded.size());

		int completeCount = 0;
		for (WaveDetails d : loaded) {
			assertNotNull(
					"FeatureData must not be null for " + d.getIdentifier(),
					d.getFeatureData());
			assertNotNull(
					"FreqData must not be null for " + d.getIdentifier(),
					d.getFreqData());
			assertTrue(
					"Entry must be complete for " + d.getIdentifier(),
					lib.isComplete(d));
			completeCount++;
		}
		assertEquals("All entries must be complete", entryCount, completeCount);

		List<WaveDetails> subset = loaded.stream().limit(10).collect(Collectors.toList());
		for (WaveDetails d : subset) {
			lib.computeSimilarities(d);
		}

		assertNoRecomputation(lib, "1000 entries + computeSimilarities subset");
	}

	// ================================================================
	// Test 9: double round-trip
	// ================================================================

	/**
	 * Saves a library to protobuf, loads it, saves the loaded library
	 * again, then loads a second time. All data must survive both
	 * round-trips with zero recomputation on the second load.
	 */
	@Test
	public void test9_doubleRoundTripNoRecomputation() {
		List<WaveDetails> entries = generateAndPrepare(audioDir, 20);
		populateSimilarities(entries);
		String prefix1 = saveToProtobuf(entries, "t9a");

		AudioLibrary firstLoad = new AudioLibrary(audioDir, SAMPLE_RATE);
		activeLibraries.add(firstLoad);
		AudioLibraryPersistence.loadLibrary(firstLoad, prefix1);
		assertAllComplete(firstLoad, 20);

		String prefix2 = new File(dataDir, "t9b").getAbsolutePath();
		AudioLibraryPersistence.saveLibrary(firstLoad, prefix2);
		firstLoad.stop();
		activeLibraries.remove(firstLoad);

		InstrumentedAudioLibrary secondLoad = loadInstrumented(prefix2);

		assertAllComplete(secondLoad, 20);
		assertNoRecomputation(secondLoad, "double round-trip second reload");
	}

	// ================================================================
	// Test 10: full app startup sequence
	// ================================================================

	/**
	 * Simulates a typical application startup sequence:
	 * load from protobuf, read similarities version, call
	 * {@code computeSimilarities()} on all entries, then save.
	 * Zero feature recomputation must occur throughout the entire sequence.
	 */
	@Test
	public void test10_appStartupSequenceNoRecomputation() {
		List<WaveDetails> entries = generateAndPrepare(audioDir, 20);
		populateSimilarities(entries);
		String prefix = saveToProtobuf(entries, "t10");

		InstrumentedAudioLibrary lib = loadInstrumented(prefix);

		lib.getSimilaritiesVersion();
		assertNoRecomputation(lib, "after getSimilaritiesVersion");

		for (WaveDetails d : lib.getAllDetails()) {
			lib.computeSimilarities(d);
		}
		assertNoRecomputation(lib, "after computeSimilarities");

		String savePrefix = new File(dataDir, "t10_save").getAbsolutePath();
		AudioLibraryPersistence.saveLibrary(lib, savePrefix);
		assertNoRecomputation(lib, "after save");

		assertAllComplete(lib, 20);
		assertNoRecomputation(lib, "full app startup sequence");
	}
}
