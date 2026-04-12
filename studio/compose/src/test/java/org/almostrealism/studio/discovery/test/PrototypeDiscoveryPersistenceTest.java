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

package org.almostrealism.studio.discovery.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataFeatureProvider;
import org.almostrealism.studio.discovery.PrototypeDiscovery;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * Manual integration test that runs {@link PrototypeDiscovery} against
 * real audio samples using a {@link ProtobufWaveDetailsStore} for
 * persistence.
 *
 * <p>Run this test twice to verify that the second run is faster because
 * all {@link org.almostrealism.audio.data.WaveDetails} data is already
 * persisted on disk from the first run.</p>
 *
 * <p>Configure via system properties:</p>
 * <ul>
 *   <li>{@code ar.test.samples} — path to audio samples directory
 *       (default: {@code ~/Music/Samples})</li>
 *   <li>{@code ar.test.store} — path to protobuf store directory
 *       (default: {@code /tmp/ar-discovery-store})</li>
 *   <li>{@code ar.test.maxPrototypes} — maximum prototypes to discover
 *       (default: {@code 10})</li>
 * </ul>
 *
 * <p>This test is marked {@link TestDepth @TestDepth(10)} to prevent
 * automatic execution. To run manually, set {@code AR_TEST_DEPTH=10}
 * or higher.</p>
 *
 * @see PrototypeDiscovery
 * @see ProtobufWaveDetailsStore
 */
public class PrototypeDiscoveryPersistenceTest extends TestSuiteBase {

	private static final int SAMPLE_RATE = 44100;
	private static final int FEATURE_FRAMES = 16;
	private static final int FEATURE_BINS = 32;

	private static final String DEFAULT_SAMPLES =
			System.getProperty("user.home") + "/Music/Samples";
	private static final String DEFAULT_STORE = "/tmp/ar-discovery-store";
	private static final int DEFAULT_MAX_PROTOTYPES = 10;

	/**
	 * Loads (or creates) an {@link AudioLibrary} backed by a
	 * {@link ProtobufWaveDetailsStore}, triggers a refresh to compute
	 * any missing feature data, runs prototype discovery, and closes
	 * the store so all data is flushed to disk.
	 *
	 * <p>On the first run the refresh computes features for every sample;
	 * on subsequent runs the store already contains all details and the
	 * refresh completes immediately.</p>
	 */
	@Test
	@TestDepth(10)
	public void discoverAndPersist() throws Exception {
		String samplesPath = System.getProperty("ar.test.samples", DEFAULT_SAMPLES);
		String storePath = System.getProperty("ar.test.store", DEFAULT_STORE);
		int maxPrototypes = Integer.getInteger("ar.test.maxPrototypes", DEFAULT_MAX_PROTOTYPES);

		File samplesDir = new File(samplesPath);
		if (!samplesDir.isDirectory()) {
			System.out.println("SKIP: Samples directory not found: " + samplesDir);
			return;
		}

		File storeDir = new File(storePath);

		System.out.println("=== PrototypeDiscovery Persistence Test ===");
		System.out.println("Samples:    " + samplesDir.getAbsolutePath());
		System.out.println("Store:      " + storeDir.getAbsolutePath());
		System.out.println("Store exists: " + storeDir.exists());
		System.out.println("Max prototypes: " + maxPrototypes);
		System.out.println();

		long startTotal = System.currentTimeMillis();

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir);
		int initialSize = store.size();
		System.out.println("Store loaded with " + initialSize + " existing entries");

		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir),
				SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(new SimpleFeatureProvider());

		long startRefresh = System.currentTimeMillis();
		System.out.println("Starting library refresh...");
		library.refresh().join();
		long refreshMs = System.currentTimeMillis() - startRefresh;
		System.out.println("Refresh completed in " + refreshMs + " ms");

		int totalIdentifiers = library.getAllIdentifiers().size();
		int storeSize = store.size();
		System.out.println("Library identifiers: " + totalIdentifiers);
		System.out.println("Store size after refresh: " + storeSize);
		System.out.println();

		long startDiscovery = System.currentTimeMillis();
		System.out.println("Running prototype discovery...");
		List<PrototypeDiscovery.PrototypeResult> prototypes =
				PrototypeDiscovery.discoverPrototypes(library, maxPrototypes, System.out::println);
		long discoveryMs = System.currentTimeMillis() - startDiscovery;
		System.out.println();

		System.out.println("=== Results ===");
		System.out.println("Discovered " + prototypes.size() + " prototypes:");
		for (int i = 0; i < prototypes.size(); i++) {
			PrototypeDiscovery.PrototypeResult prototype = prototypes.get(i);
			System.out.printf("  %d. %s (community=%d, centrality=%.6f)%n",
					i + 1, prototype.identifier(),
					prototype.communitySize(), prototype.centrality());
		}
		System.out.println();

		library.stop();
		store.flush();
		store.close();

		long totalMs = System.currentTimeMillis() - startTotal;

		System.out.println("=== Timing ===");
		System.out.println("Refresh:   " + refreshMs + " ms");
		System.out.println("Discovery: " + discoveryMs + " ms");
		System.out.println("Total:     " + totalMs + " ms");
		System.out.println();
		System.out.println("Run this test again — second run should be significantly faster.");
	}

	/**
	 * Simple feature provider that computes spectral features from audio
	 * data using a basic DFT at logarithmically spaced frequency bands.
	 *
	 * <p>For production use with large libraries, replace this with
	 * {@code AutoEncoderFeatureProvider} backed by {@code OnnxAutoEncoder}
	 * for higher-quality embeddings.</p>
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
			return FEATURE_FRAMES / 0.25;
		}
	}
}
