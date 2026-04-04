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
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataFeatureProvider;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
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
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Tests the complete drawing-to-feature pipeline: frequency data is included
 * in the library, the library synthesizes audio via IFFT, computes features,
 * and returns complete WaveDetails.
 *
 * <p>These tests cover the failure modes encountered during the drawing-based
 * generation implementation:</p>
 * <ul>
 *   <li>{@link AudioLibrary#get(String)} must return incomplete entries (no
 *       completeness gate)</li>
 *   <li>{@link AudioLibrary#getDetailsAwait(String, long)} must handle entries
 *       with only frequency data (no raw audio)</li>
 *   <li>{@link WaveDetailsFactory#forExisting} must synthesize audio from
 *       frequency data when raw audio is absent</li>
 *   <li>Feature computation must not fail due to the
 *       {@code WaveDataProviderAdapter} static cache returning stale null
 *       entries</li>
 *   <li>The protobuf store must preserve frequency data through
 *       serialization round-trips</li>
 * </ul>
 *
 * @see WaveDetailsFactory#forExisting
 * @see AudioLibrary#resolveProvider
 */
public class DrawingFeaturePipelineTest extends TestSuiteBase {

	private static final int SAMPLE_RATE = 44100;
	private static final int FREQ_BINS = 32;
	private static final int FREQ_FRAMES = 100;
	private static final double FREQ_SAMPLE_RATE = 100.0;
	private static final int FEATURE_FRAMES = 8;
	private static final int FEATURE_BINS = 16;

	private Path tempDir;
	private AudioLibrary library;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("drawing-pipeline-test");
		library = new AudioLibrary(tempDir.toFile(), SAMPLE_RATE);
		library.getWaveDetailsFactory().setFeatureProvider(new TestFeatureProvider());
	}

	@After
	public void tearDown() throws IOException {
		if (library != null) library.stop();
		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			}
		}
	}

	/**
	 * Verifies that {@link AudioLibrary#get(String)} returns entries that
	 * have only frequency data (no audio waveform, no features). This is
	 * the state of a drawing when it is first included.
	 */
	@Test(timeout = 10000)
	public void getReturnsFreqDataOnlyEntry() {
		WaveDetails drawing = createDrawingDetails("freq-only-id");
		library.include(drawing);

		WaveDetails retrieved = library.get("freq-only-id");
		Assert.assertNotNull("get() must return freq-data-only entries", retrieved);
		Assert.assertNotNull("freqData should be present", retrieved.getFreqData());
		Assert.assertNull("data should be null before processing", retrieved.getData());
		Assert.assertNull("featureData should be null before processing", retrieved.getFeatureData());
	}

	/**
	 * Verifies that {@link AudioLibrary#getDetailsAwait(String, long)}
	 * computes features for an entry that only has frequency data. This
	 * exercises the full drawing pipeline: freq data -> IFFT synthesis ->
	 * feature extraction.
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void getDetailsAwaitComputesFeaturesFromFreqData() {
		WaveDetails drawing = createDrawingDetails("draw-feat-id");
		library.include(drawing);

		WaveDetails result = library.getDetailsAwait("draw-feat-id", 120);
		Assert.assertNotNull("getDetailsAwait must return a result", result);
		Assert.assertNotNull("featureData must be computed", result.getFeatureData());
		Assert.assertEquals(FEATURE_FRAMES, result.getFeatureFrameCount());
		Assert.assertEquals(FEATURE_BINS, result.getFeatureBinCount());
	}

	/**
	 * Verifies that the pipeline works when the library uses a protobuf
	 * store (which drops raw audio during serialization). The frequency
	 * data must survive the round-trip and feature computation must still
	 * succeed.
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void protobufStorePreservesFreqDataForFeatureComputation() throws IOException {
		Path storeDir = tempDir.resolve("store");
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary storeLibrary = new AudioLibrary(
				new FileWaveDataProviderNode(tempDir.toFile()), SAMPLE_RATE, store);
		storeLibrary.getWaveDetailsFactory().setFeatureProvider(new TestFeatureProvider());

		try {
			WaveDetails drawing = createDrawingDetails("store-draw-id");
			storeLibrary.include(drawing);

			// Verify freq data survives store round-trip
			WaveDetails fromStore = store.get("store-draw-id");
			Assert.assertNotNull("Store must return the entry", fromStore);
			Assert.assertNotNull("freqData must survive protobuf serialization",
					fromStore.getFreqData());

			// Verify full pipeline produces features
			WaveDetails result = storeLibrary.getDetailsAwait("store-draw-id", 120);
			Assert.assertNotNull("getDetailsAwait must return a result", result);
			Assert.assertNotNull("featureData must be computed from freq data",
					result.getFeatureData());
		} finally {
			storeLibrary.stop();
			store.close();
		}
	}

	/**
	 * Verifies that {@link WaveDetailsFactory#forExisting} synthesizes
	 * audio from frequency data and computes features in a single pass.
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void forExistingSynthesizesAudioFromFreqData() {
		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);
		factory.setFeatureProvider(new TestFeatureProvider());

		WaveDetails drawing = createDrawingDetails("factory-test-id");
		Assert.assertNull("data should be null before forExisting", drawing.getData());

		WaveDetails result = factory.forExisting(drawing);
		Assert.assertNotNull("forExisting must return a result", result);
		Assert.assertNotNull("data must be synthesized from freqData", result.getData());
		Assert.assertNotNull("featureData must be computed", result.getFeatureData());
	}

	/**
	 * Verifies that calling getDetailsAwait twice for the same identifier
	 * does not fail due to the WaveDataProviderAdapter static cache
	 * returning stale entries.
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void repeatedGetDetailsAwaitDoesNotCacheStaleNull() {
		WaveDetails drawing = createDrawingDetails("repeat-id");
		library.include(drawing);

		WaveDetails first = library.getDetailsAwait("repeat-id", 120);
		Assert.assertNotNull("First call must succeed", first);
		Assert.assertNotNull("First call must produce features", first.getFeatureData());

		WaveDetails second = library.getDetailsAwait("repeat-id", 120);
		Assert.assertNotNull("Second call must succeed", second);
		Assert.assertNotNull("Second call must still have features", second.getFeatureData());
	}

	/**
	 * Verifies that getDetailsNow returns a result for a freq-data-only
	 * entry (submits a job and may return empty, but must not throw).
	 */
	@Test(timeout = 10000)
	public void getDetailsNowDoesNotThrowForFreqOnlyEntry() {
		WaveDetails drawing = createDrawingDetails("now-id");
		library.include(drawing);

		// Should not throw - may return empty if job hasn't completed
		library.getDetailsNow("now-id");
	}

	/**
	 * Verifies that feature data shape is preserved correctly through the
	 * store/transpose chain. The composer expects 2D features [bins, frames]
	 * from getFeatureData(true).
	 */
	@Test(timeout = 300000)
	@TestDepth(2)
	public void featureDataShapeIsCorrectForComposer() {
		WaveDetails drawing = createDrawingDetails("shape-test-id");
		library.include(drawing);

		WaveDetails result = library.getDetailsAwait("shape-test-id", 120);
		Assert.assertNotNull("Must return result", result);
		Assert.assertNotNull("Must have featureData", result.getFeatureData());

		PackedCollection featureData = result.getFeatureData();
		log("featureData shape: " + featureData.getShape());
		Assert.assertEquals("featureData should be 2D",
				2, featureData.getShape().getDimensions());

		PackedCollection transposed = result.getFeatureData(true);
		log("transposed shape: " + transposed.getShape());
		Assert.assertEquals("transposed featureData should be 2D",
				2, transposed.getShape().getDimensions());
		Assert.assertEquals("transposed dim 0 should be FEATURE_BINS",
				FEATURE_BINS, transposed.getShape().length(0));
		Assert.assertEquals("transposed dim 1 should be FEATURE_FRAMES",
				FEATURE_FRAMES, transposed.getShape().length(1));
	}

	// ── Test helpers ─────────────────────────────────────────────────

	/**
	 * Creates a {@link WaveDetails} that simulates a spatial drawing:
	 * has frequency data and metadata but no raw audio or features.
	 */
	private WaveDetails createDrawingDetails(String identifier) {
		WaveDetails details = new WaveDetails(identifier, SAMPLE_RATE);
		details.setFreqBinCount(FREQ_BINS);
		details.setFreqFrameCount(FREQ_FRAMES);
		details.setFreqSampleRate(FREQ_SAMPLE_RATE);
		details.setFreqChannelCount(1);

		// Populate frequency data with a simple harmonic pattern
		PackedCollection freqData = new PackedCollection(FREQ_FRAMES * FREQ_BINS);
		for (int f = 0; f < FREQ_FRAMES; f++) {
			for (int b = 0; b < FREQ_BINS; b++) {
				double value = Math.sin(2.0 * Math.PI * b / FREQ_BINS) *
						Math.cos(2.0 * Math.PI * f / FREQ_FRAMES);
				freqData.setMem(f * FREQ_BINS + b, Math.max(0, value * 10));
			}
		}
		details.setFreqData(freqData);

		return details;
	}

	/**
	 * Minimal feature provider for testing. Produces 2D features of shape
	 * ({@link #FEATURE_FRAMES}, {@link #FEATURE_BINS}) from any audio
	 * input by computing simple spectral energy in windowed segments.
	 */
	static class TestFeatureProvider implements WaveDataFeatureProvider {
		@Override
		public PackedCollection computeFeatures(WaveData waveData) {
			int totalFrames = waveData.getFrameCount();
			int windowSize = Math.max(1, totalFrames / FEATURE_FRAMES);

			PackedCollection features = new PackedCollection(FEATURE_FRAMES, FEATURE_BINS);
			PackedCollection data = waveData.getChannelData(0);

			for (int f = 0; f < FEATURE_FRAMES; f++) {
				int start = f * windowSize;
				for (int b = 0; b < FEATURE_BINS; b++) {
					double energy = 0;
					for (int i = start; i < Math.min(start + windowSize, totalFrames); i++) {
						if (i < data.getMemLength()) {
							double sample = data.toDouble(i);
							double angle = 2.0 * Math.PI * (b + 1) * i / SAMPLE_RATE;
							energy += sample * Math.cos(angle);
						}
					}
					features.setMem(f * FEATURE_BINS + b, Math.abs(energy) / windowSize);
				}
			}

			return features;
		}

		@Override
		public int getAudioSampleRate() { return SAMPLE_RATE; }

		@Override
		public double getFeatureSampleRate() { return FEATURE_FRAMES / 1.0; }
	}
}
