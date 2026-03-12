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
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataFeatureProvider;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.audio.persistence.AudioLibraryPersistence;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Verifies that loading an {@link AudioLibrary} from a protobuf file does NOT
 * trigger audio reloading or feature recomputation for entries whose data is
 * already complete.
 *
 * <p>This test uses REAL audio files on disk, REAL feature computation through
 * the {@link org.almostrealism.audio.data.WaveDetailsFactory} pipeline, and a
 * counting {@link FileWaveDataProvider} wrapper to detect any audio loading
 * after a protobuf round-trip. It does NOT subclass {@link AudioLibrary} or
 * construct {@link WaveDetails} with pre-populated synthetic data.</p>
 *
 * <p>The instrumentation strategy wraps {@link FileWaveDataProvider} to count
 * calls to {@code load()}, which is the method that actually reads audio data
 * from a WAV file on disk. Any call to {@code load()} after loading from
 * protobuf indicates unwanted recomputation.</p>
 *
 * @see AudioLibrary
 * @see AudioLibraryPersistence
 */
public class RealProtobufRecomputeTest extends TestSuiteBase {

	private static final int SAMPLE_RATE = 44100;
	private static final int AUDIO_FRAMES = 2205;
	private static final int FILE_COUNT = 5;

	private File audioDir;
	private File dataDir;
	private List<AudioLibrary> activeLibraries;
	private List<File> generatedFiles;

	@Before
	public void setUp() throws Exception {
		audioDir = Files.createTempDirectory("recompute-test-audio").toFile();
		dataDir = Files.createTempDirectory("recompute-test-data").toFile();
		activeLibraries = new ArrayList<>();
		generatedFiles = new ArrayList<>();
	}

	@After
	public void tearDown() {
		for (AudioLibrary lib : activeLibraries) {
			lib.stop();
		}
		activeLibraries.clear();
		deleteRecursive(audioDir);
		deleteRecursive(dataDir);
	}

	// ================================================================
	// Counting FileWaveDataProvider — detects audio loading
	// ================================================================

	/**
	 * A {@link FileWaveDataProvider} wrapper that counts calls to
	 * {@code load()}. Each call to {@code load()} means the audio
	 * file was read from disk, which should not happen for entries
	 * already loaded from protobuf.
	 */
	private static class CountingFileWaveDataProvider extends FileWaveDataProvider {
		private final AtomicInteger loadCount;

		CountingFileWaveDataProvider(String path, AtomicInteger loadCount) {
			super(path);
			this.loadCount = loadCount;
		}

		@Override
		protected WaveData load() {
			loadCount.incrementAndGet();
			return super.load();
		}
	}

	// ================================================================
	// Counting file tree — returns counting providers
	// ================================================================

	/**
	 * A {@link FileWaveDataProviderTree} that returns
	 * {@link CountingFileWaveDataProvider} instances instead of plain
	 * {@link FileWaveDataProvider}. This allows detection of audio
	 * loading without subclassing {@link AudioLibrary}.
	 */
	private static class CountingFileTree
			implements FileWaveDataProviderTree<CountingFileTree>,
			Supplier<FileWaveDataProvider> {
		private final File file;
		private final AtomicInteger loadCount;

		CountingFileTree(File f, AtomicInteger loadCount) {
			this.file = f;
			this.loadCount = loadCount;
		}

		@Override
		public Collection<CountingFileTree> getChildren() {
			if (!file.isDirectory()) return Collections.emptyList();
			File[] children = file.listFiles();
			if (children == null) return Collections.emptyList();
			return Stream.of(children)
					.map(f -> new CountingFileTree(f, loadCount))
					.collect(Collectors.toList());
		}

		@Override
		public FileWaveDataProvider get() {
			if (file.isDirectory()) return null;
			if (!file.exists()) return null;
			String name = file.getName();
			if (name.length() < 4) return null;
			String ext = name.substring(name.length() - 4);
			if (!ext.contains("wav") && !ext.contains("WAV")) return null;
			try {
				return new CountingFileWaveDataProvider(
						file.getCanonicalPath(), loadCount);
			} catch (IOException e) {
				return null;
			}
		}

		@Override
		public String getResourcePath() {
			try {
				return file.getCanonicalPath();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public String getRelativePath(String path) {
			return FileWaveDataProviderTree.getRelativePath(file, path);
		}

		@Override
		public String signature() {
			return file.getPath();
		}
	}

	// ================================================================
	// Simple test feature provider
	// ================================================================

	/**
	 * A minimal {@link WaveDataFeatureProvider} that extracts a small
	 * feature matrix from real audio data. This produces non-null
	 * featureData through the real {@link org.almostrealism.audio.data.WaveDetailsFactory}
	 * pipeline without requiring an ML model.
	 */
	private static class SimpleFeatureProvider implements WaveDataFeatureProvider {
		private final AtomicInteger computeCount = new AtomicInteger(0);

		@Override
		public PackedCollection computeFeatures(WaveData waveData) {
			computeCount.incrementAndGet();
			int frames = Math.min(8, waveData.getFrameCount());
			int bins = 4;
			PackedCollection features = new PackedCollection(frames, bins);
			for (int f = 0; f < frames; f++) {
				for (int b = 0; b < bins; b++) {
					int idx = f * bins + b;
					int srcIdx = f * (waveData.getFrameCount() / frames) + b;
					double val = srcIdx < waveData.getFrameCount()
							? waveData.getData().toDouble(srcIdx)
							: 0.0;
					features.setMem(idx, val);
				}
			}
			return features;
		}

		@Override
		public int getAudioSampleRate() {
			return SAMPLE_RATE;
		}

		@Override
		public double getFeatureSampleRate() {
			return 100.0;
		}

		int getComputeCount() {
			return computeCount.get();
		}
	}

	// ================================================================
	// Helper methods
	// ================================================================

	/**
	 * Generates a stereo sine wave WAV file.
	 */
	private File generateAudioFile(int index, double frequency) {
		PackedCollection data = new PackedCollection(2, AUDIO_FRAMES);
		for (int i = 0; i < AUDIO_FRAMES; i++) {
			double t = (double) i / SAMPLE_RATE;
			double value = 0.5 * Math.sin(2 * Math.PI * frequency * t);
			data.setMem(i, value);
			data.setMem(AUDIO_FRAMES + i, value);
		}

		WaveData waveData = new WaveData(data, SAMPLE_RATE);
		File file = new File(audioDir, "audio_" + index + ".wav");
		assertTrue("Failed to save audio file: " + file, waveData.save(file));
		return file;
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
	// Test: protobuf load does NOT trigger audio reloading
	// ================================================================

	/**
	 * End-to-end test that:
	 * <ol>
	 *   <li>Generates real WAV audio files on disk</li>
	 *   <li>Creates an {@link AudioLibrary} with a real feature provider,
	 *       computes details (including features) for each file via
	 *       {@code getDetailsAwait}</li>
	 *   <li>Saves the library to protobuf via
	 *       {@link AudioLibraryPersistence#saveLibrary}</li>
	 *   <li>Creates a FRESH {@link AudioLibrary} with counting providers
	 *       (no shared state with the first library)</li>
	 *   <li>Loads from protobuf via
	 *       {@link AudioLibraryPersistence#loadLibrary}</li>
	 *   <li>Calls {@code getAllDetails()} and {@code refresh()}</li>
	 *   <li>Asserts that the counting providers' load counter is ZERO</li>
	 * </ol>
	 */
	@Test
	public void protobufLoadDoesNotReloadAudio() throws Exception {
		// ---- Phase 1: Generate files and compute real features ----
		for (int i = 0; i < FILE_COUNT; i++) {
			double frequency = 220.0 + i * 110.0;
			generatedFiles.add(generateAudioFile(i, frequency));
		}

		SimpleFeatureProvider featureProvider = new SimpleFeatureProvider();
		AudioLibrary computeLib = new AudioLibrary(audioDir, SAMPLE_RATE);
		activeLibraries.add(computeLib);
		computeLib.getWaveDetailsFactory().setFeatureProvider(featureProvider);

		// Compute real details for each file
		for (File f : generatedFiles) {
			WaveDetails details = computeLib.getDetailsAwait(
					new FileWaveDataProvider(f), true);
			assertNotNull("Details should be computed for " + f.getName(),
					details);
			assertNotNull("FreqData should be computed for " + f.getName(),
					details.getFreqData());
			assertNotNull("FeatureData should be computed for " + f.getName(),
					details.getFeatureData());
			assertTrue("Entry should be complete for " + f.getName(),
					computeLib.isComplete(details));
		}

		int initialComputeCount = featureProvider.getComputeCount();
		assertTrue("Feature provider should have been called at least " +
						FILE_COUNT + " times during initial computation",
				initialComputeCount >= FILE_COUNT);

		// Verify all entries are complete before saving
		Collection<WaveDetails> allBefore = computeLib.getAllDetails();
		assertEquals("Should have " + FILE_COUNT + " entries before save",
				FILE_COUNT, allBefore.size());
		for (WaveDetails d : allBefore) {
			assertTrue("All entries must be complete before save",
					computeLib.isComplete(d));
		}

		// ---- Phase 2: Save to protobuf ----
		String prefix = new File(dataDir, "test").getAbsolutePath();
		AudioLibraryPersistence.saveLibrary(computeLib, prefix);

		computeLib.stop();
		activeLibraries.remove(computeLib);

		// ---- Phase 3: Fresh library with counting providers ----
		AtomicInteger audioLoadCount = new AtomicInteger(0);
		CountingFileTree countingTree = new CountingFileTree(audioDir, audioLoadCount);

		SimpleFeatureProvider reloadFeatureProvider = new SimpleFeatureProvider();
		AudioLibrary reloadLib = new AudioLibrary(countingTree, SAMPLE_RATE);
		activeLibraries.add(reloadLib);

		// Match real app: set feature provider BEFORE loading protobuf
		reloadLib.getWaveDetailsFactory().setFeatureProvider(reloadFeatureProvider);

		// Load from protobuf (the standard app startup path)
		AudioLibraryPersistence.loadLibrary(reloadLib, prefix);

		// ---- Phase 4: App startup operations ----
		// Call getAllDetails — should use cached/loaded data, not reload audio
		Collection<WaveDetails> allAfter = reloadLib.getAllDetails();
		assertEquals("Should have " + FILE_COUNT + " entries after load",
				FILE_COUNT, allAfter.size());

		// Verify loaded data is complete
		for (WaveDetails d : allAfter) {
			assertNotNull("FreqData must survive protobuf round-trip for " +
					d.getIdentifier(), d.getFreqData());
			assertNotNull("FeatureData must survive protobuf round-trip for " +
					d.getIdentifier(), d.getFeatureData());
			assertTrue("Entry must be complete after protobuf load for " +
					d.getIdentifier(), reloadLib.isComplete(d));
		}

		// Call getDetailsAwait for each file — matches real app UI requests
		for (File f : generatedFiles) {
			WaveDetails details = reloadLib.getDetailsAwait(
					new CountingFileWaveDataProvider(
							f.getCanonicalPath(), audioLoadCount), true);
			assertNotNull("Details should exist after protobuf load for " +
					f.getName(), details);
		}

		// Call refresh — should skip all files since data is complete
		reloadLib.refresh();
		reloadLib.awaitRefresh().get(60, TimeUnit.SECONDS);

		// Verify feature provider on reload lib was NOT called
		assertEquals(
				"Feature provider should NOT have been called after protobuf " +
						"load with complete data. Compute count = " +
						reloadFeatureProvider.getComputeCount() + " (expected 0).",
				0, reloadFeatureProvider.getComputeCount());

		// ---- Phase 5: Assert no audio was loaded ----
		assertEquals(
				"Audio files should NOT have been loaded from disk after " +
						"protobuf load. Load count = " + audioLoadCount.get() +
						" (expected 0). This indicates the library is " +
						"unnecessarily reloading/recomputing audio data for " +
						"entries that already have complete data from protobuf.",
				0, audioLoadCount.get());
	}

	/**
	 * Verifies that {@link WaveDetailsFactory#forExisting(WaveDetails)} does NOT
	 * recompute features when they are already present. This is the root cause of
	 * the protobuf recomputation bug: {@code forExisting()} unconditionally calls
	 * the feature provider even when featureData already exists.
	 *
	 * <p>While the caller ({@code AudioLibrary.computeDetails}) guards against
	 * reaching this code path for complete entries, the fix belongs in
	 * {@code forExisting()} itself — a method should not silently discard and
	 * recompute valid data.</p>
	 */
	@Test
	public void forExistingDoesNotRecomputeFeatures() throws Exception {
		// Generate a real audio file
		File audioFile = generateAudioFile(0, 440.0);

		SimpleFeatureProvider featureProvider = new SimpleFeatureProvider();

		// Create a factory with the feature provider
		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);
		factory.setFeatureProvider(featureProvider);

		// Compute details from real audio (loads audio + computes freq + features)
		FileWaveDataProvider provider = new FileWaveDataProvider(audioFile);
		WaveDetails details = factory.forProvider(provider);

		assertNotNull("FreqData should be computed", details.getFreqData());
		assertNotNull("FeatureData should be computed", details.getFeatureData());
		assertEquals("Feature provider should have been called once",
				1, featureProvider.getComputeCount());

		// Save a reference to the original featureData
		PackedCollection originalFeatureData = details.getFeatureData();

		// Call forExisting() again — features already exist, should NOT recompute
		factory.forExisting(details);

		assertEquals(
				"Feature provider should NOT have been called again by " +
						"forExisting() when featureData is already present. " +
						"Compute count = " + featureProvider.getComputeCount() +
						" (expected 1). This is the root cause: forExisting() " +
						"unconditionally recomputes features without checking " +
						"if they already exist.",
				1, featureProvider.getComputeCount());

		assertTrue(
				"FeatureData reference should not have changed",
				originalFeatureData == details.getFeatureData());
	}
}
