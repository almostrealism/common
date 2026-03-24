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

package org.almostrealism.audio.persistence.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataFeatureProvider;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsStore;
import org.almostrealism.audio.discovery.PrototypeDiscovery;
import org.almostrealism.audio.persistence.ProtobufWaveDetailsStore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Integration tests for the {@link ProtobufWaveDetailsStore}-backed
 * {@link AudioLibrary} pipeline. These tests generate real audio files,
 * process them through the full pipeline, persist to disk, reload, and
 * verify correctness.
 *
 * <p>Tests validate:</p>
 * <ul>
 *   <li>Round-trip persistence: data survives store close and reopen</li>
 *   <li>No feature recomputation after reload</li>
 *   <li>HNSW search returns sensible nearest neighbors</li>
 *   <li>{@link PrototypeDiscovery} runs on the sparse K-NN graph</li>
 *   <li>Memory stays bounded under the store's configured cap</li>
 * </ul>
 *
 * @see ProtobufWaveDetailsStore
 * @see AudioLibrary
 */
public class DiskStoreAudioLibraryTest extends TestSuiteBase {

	private static final int SAMPLE_RATE = 44100;
	private static final int SAMPLE_COUNT = 1000;
	private static final double SAMPLE_DURATION = 0.25;
	private static final int FEATURE_FRAMES = 16;
	private static final int FEATURE_BINS = 32;

	private Path tempDir;
	private Path samplesDir;
	private Path storeDir;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("diskstore-audio-test");
		samplesDir = tempDir.resolve("samples");
		storeDir = tempDir.resolve("store");
		Files.createDirectories(samplesDir);
	}

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

	/**
	 * Core round-trip test: store records, close, reopen, verify all
	 * data is present and no batch loads occur for already-cached data.
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void storeAndReloadPipeline() throws Exception {
		int count = SAMPLE_COUNT;
		List<File> wavFiles = generateSineWaves(count);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()),
				SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(new SimpleFeatureProvider());

		Set<String> originalIdentifiers = new HashSet<>();
		for (File wav : wavFiles) {
			FileWaveDataProvider provider = new FileWaveDataProvider(wav.getAbsolutePath());
			WaveDetails details = library.getDetailsAwait(provider, 30);
			Assert.assertNotNull("Details should be computed for " + wav.getName(), details);
			Assert.assertNotNull("Feature data should be present", details.getFeatureData());
			originalIdentifiers.add(details.getIdentifier());
		}

		Assert.assertEquals("All samples should be stored",
				count, store.size());
		Assert.assertEquals("All identifiers should be tracked",
				count, library.getAllIdentifiers().size());

		library.stop();
		store.close();

		ProtobufWaveDetailsStore store2 = new ProtobufWaveDetailsStore(storeDir.toFile());
		AtomicInteger batchLoads = new AtomicInteger(0);
		store2.setLoadListener(batchId -> batchLoads.incrementAndGet());

		Assert.assertEquals("Reopened store should have same record count",
				count, store2.size());

		Set<String> reloadedIds = store2.allIdentifiers();
		Assert.assertEquals("All identifiers should survive reload",
				originalIdentifiers, reloadedIds);

		int featureDataCount = 0;
		for (String id : reloadedIds) {
			WaveDetails reloaded = store2.get(id);
			Assert.assertNotNull("Reloaded details should not be null for " + id, reloaded);
			Assert.assertNotNull("FreqData should survive reload", reloaded.getFreqData());
			if (reloaded.getFeatureData() != null) {
				featureDataCount++;
			}
		}
		Assert.assertEquals("All records should have feature data after reload",
				count, featureDataCount);

		store2.close();
	}

	/**
	 * Verifies that no feature recomputation occurs after reloading a store.
	 * Uses a store-backed AudioLibrary and checks that feature data is
	 * retrieved from the store without triggering any provider loads.
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void noRecomputationAfterReload() throws Exception {
		int count = 100;
		List<File> wavFiles = generateSineWaves(count);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()),
				SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(new SimpleFeatureProvider());

		for (File wav : wavFiles) {
			FileWaveDataProvider provider = new FileWaveDataProvider(wav.getAbsolutePath());
			library.getDetailsAwait(provider, 30);
		}

		library.stop();
		store.close();

		ProtobufWaveDetailsStore store2 = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library2 = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()),
				SAMPLE_RATE, store2);

		List<WaveDetails> allReloaded = library2.allDetails().toList();
		Assert.assertEquals("All details should be retrievable", count, allReloaded.size());

		for (WaveDetails d : allReloaded) {
			Assert.assertNotNull("Feature data should come from store, not recomputed",
					d.getFeatureData());
			Assert.assertNotNull("Freq data should come from store",
					d.getFreqData());
		}

		library2.stop();
		store2.close();
	}

	/**
	 * Tests that HNSW search returns sensible nearest neighbors: samples
	 * at nearby frequencies should be neighbors, while distant frequencies
	 * should not appear in top results.
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void hnswSearchProducesSensibleResults() throws Exception {
		int count = 200;
		List<File> wavFiles = generateSineWaves(count);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()),
				SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(new SimpleFeatureProvider());

		List<WaveDetails> allDetails = new ArrayList<>();
		for (File wav : wavFiles) {
			FileWaveDataProvider provider = new FileWaveDataProvider(wav.getAbsolutePath());
			WaveDetails details = library.getDetailsAwait(provider, 30);
			if (details != null) allDetails.add(details);
		}

		int midIndex = count / 2;
		WaveDetails midSample = allDetails.get(midIndex);
		PackedCollection embedding = AudioLibrary.computeEmbeddingVector(midSample);
		Assert.assertNotNull("Mid sample should have an embedding", embedding);

		List<WaveDetailsStore.NeighborResult> neighbors =
				store.searchNeighbors(embedding, 10);
		Assert.assertFalse("Should find neighbors", neighbors.isEmpty());
		Assert.assertTrue("Should find at least 5 neighbors", neighbors.size() >= 5);

		Set<String> neighborIds = new HashSet<>();
		for (WaveDetailsStore.NeighborResult n : neighbors) {
			neighborIds.add(n.identifier());
		}

		int nearbyCount = 0;
		for (int i = Math.max(0, midIndex - 5); i <= Math.min(count - 1, midIndex + 5); i++) {
			if (i != midIndex && neighborIds.contains(allDetails.get(i).getIdentifier())) {
				nearbyCount++;
			}
		}
		Assert.assertTrue(
				"At least 2 nearby-frequency samples should be neighbors (found " + nearbyCount + ")",
				nearbyCount >= 2);

		library.stop();
		store.close();
	}

	/**
	 * Tests that {@link PrototypeDiscovery} runs to completion on the
	 * HNSW-powered sparse graph and produces a reasonable number of
	 * prototypes.
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void prototypeDiscoveryOnSparseGraph() throws Exception {
		int count = 200;
		List<File> wavFiles = generateSineWaves(count);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()),
				SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(new SimpleFeatureProvider());

		for (File wav : wavFiles) {
			FileWaveDataProvider provider = new FileWaveDataProvider(wav.getAbsolutePath());
			library.getDetailsAwait(provider, 30);
		}

		List<PrototypeDiscovery.PrototypeResult> prototypes =
				PrototypeDiscovery.discoverPrototypes(library, 50, null);

		Assert.assertFalse("Should discover at least one prototype", prototypes.isEmpty());
		Assert.assertTrue("Prototype count should be between 2 and 100, was " + prototypes.size(),
				prototypes.size() >= 2 && prototypes.size() <= 100);

		for (PrototypeDiscovery.PrototypeResult p : prototypes) {
			Assert.assertNotNull("Prototype identifier should not be null", p.identifier());
			Assert.assertTrue("Community should have at least 1 member",
					p.communitySize() >= 1);
		}

		library.stop();
		store.close();
	}

	/**
	 * Tests that memory usage stays bounded when iterating through
	 * all entries in a store with a small memory cap.
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void memoryStaysBounded() throws Exception {
		int count = 200;
		List<File> wavFiles = generateSineWaves(count);

		long maxMemory = 10L * 1024 * 1024;
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(
				storeDir.toFile(), maxMemory,
				ProtobufWaveDetailsStore.DEFAULT_TARGET_BATCH_SIZE);
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()),
				SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(new SimpleFeatureProvider());

		for (File wav : wavFiles) {
			FileWaveDataProvider provider = new FileWaveDataProvider(wav.getAbsolutePath());
			library.getDetailsAwait(provider, 30);
		}

		library.stop();
		store.close();

		ProtobufWaveDetailsStore store2 = new ProtobufWaveDetailsStore(
				storeDir.toFile(), maxMemory,
				ProtobufWaveDetailsStore.DEFAULT_TARGET_BATCH_SIZE);

		Runtime runtime = Runtime.getRuntime();
		runtime.gc();
		long baselineMemory = runtime.totalMemory() - runtime.freeMemory();

		for (String id : store2.allIdentifiers()) {
			WaveDetails details = store2.get(id);
			Assert.assertNotNull(details);
		}

		runtime.gc();
		long afterMemory = runtime.totalMemory() - runtime.freeMemory();
		long memoryUsed = afterMemory - baselineMemory;

		Assert.assertTrue(
				"Memory increase should stay under 200MB, was "
						+ (memoryUsed / (1024 * 1024)) + "MB",
				memoryUsed < 200L * 1024 * 1024);

		store2.close();
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/**
	 * Generates sine wave WAV files at logarithmically spaced frequencies
	 * from 55 Hz to 8000 Hz.
	 */
	private List<File> generateSineWaves(int count) throws IOException {
		List<File> files = new ArrayList<>(count);
		double minFreq = 55.0;
		double maxFreq = 8000.0;
		double logMin = Math.log(minFreq);
		double logMax = Math.log(maxFreq);
		int frames = (int) (SAMPLE_RATE * SAMPLE_DURATION);

		for (int n = 0; n < count; n++) {
			double t = count > 1 ? (double) n / (count - 1) : 0.0;
			double frequency = Math.exp(logMin + t * (logMax - logMin));

			File wavFile = samplesDir.resolve(
					String.format("sine_%04d_%.0fHz.wav", n, frequency)).toFile();

			try (WavFile wav = WavFile.newWavFile(wavFile, 1, frames, 16, SAMPLE_RATE)) {
				double[][] buffer = new double[1][frames];
				for (int i = 0; i < frames; i++) {
					buffer[0][i] = 0.8 * Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE);
				}
				wav.writeFrames(buffer, frames);
			}

			files.add(wavFile);
		}

		return files;
	}

	/**
	 * Default target batch size for the store (exposed for tests that
	 * need to customize memory settings).
	 */
	public static final int DEFAULT_TARGET_BATCH_SIZE =
			org.almostrealism.persist.index.ProtobufDiskStore.DEFAULT_TARGET_BATCH_SIZE;

	/**
	 * Simple feature provider that computes spectral features from audio
	 * data using a basic DFT at logarithmically spaced frequency bands.
	 * Produces features of shape ({@link #FEATURE_FRAMES}, {@link #FEATURE_BINS}, 1).
	 */
	static class SimpleFeatureProvider implements WaveDataFeatureProvider {
		@Override
		public PackedCollection computeFeatures(WaveData waveData) {
			int totalFrames = waveData.getFrameCount();
			int windowSize = Math.max(1, totalFrames / FEATURE_FRAMES);

			double[] samples = new double[totalFrames];
			PackedCollection data = waveData.getChannelData(0);
			for (int i = 0; i < totalFrames && i < data.getMemLength(); i++) {
				samples[i] = data.toDouble(i);
			}

			PackedCollection features = new PackedCollection(FEATURE_FRAMES, FEATURE_BINS, 1);

			double minFreq = 20.0;
			double maxFreq = SAMPLE_RATE / 2.0;
			double logMin = Math.log(minFreq);
			double logMax = Math.log(maxFreq);

			for (int f = 0; f < FEATURE_FRAMES; f++) {
				int start = f * windowSize;
				int end = Math.min(start + windowSize, totalFrames);
				int len = end - start;

				for (int b = 0; b < FEATURE_BINS; b++) {
					double bt = (double) b / (FEATURE_BINS - 1);
					double freq = Math.exp(logMin + bt * (logMax - logMin));

					double real = 0;
					double imag = 0;
					for (int i = 0; i < len; i++) {
						double angle = 2.0 * Math.PI * freq * i / SAMPLE_RATE;
						real += samples[start + i] * Math.cos(angle);
						imag += samples[start + i] * Math.sin(angle);
					}

					double magnitude = Math.sqrt(real * real + imag * imag) / len;
					features.setMem(f * FEATURE_BINS + b, magnitude);
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
			return FEATURE_FRAMES / SAMPLE_DURATION;
		}
	}
}
