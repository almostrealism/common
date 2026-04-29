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

import ai.onnxruntime.OrtException;
import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.studio.discovery.PrototypeDiscovery;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
import org.almostrealism.studio.ml.AutoEncoderFeatureProvider;
import org.almostrealism.ml.audio.OnnxAutoEncoder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Integration test that runs {@link PrototypeDiscovery} against real audio
 * samples using the {@link OnnxAutoEncoder} for feature computation and
 * {@link ProtobufWaveDetailsStore} for persistence.
 *
 * <p>This test uses the ONNX-based autoencoder to compute high-quality
 * latent embeddings for audio samples, enabling better similarity search
 * and prototype discovery.</p>
 *
 * <p>Configure via system properties:</p>
 * <ul>
 *   <li>{@code ar.test.samples} — path to audio samples directory
 *       (default: {@code /samples} or {@code ~/Music/Samples})</li>
 *   <li>{@code ar.test.store} — path to protobuf store directory
 *       (default: {@code /tmp/ar-onnx-discovery-store})</li>
 *   <li>{@code ar.test.models} — path to ONNX models directory
 *       (default: {@code /models} or {@code ../models})</li>
 *   <li>{@code ar.test.maxPrototypes} — maximum prototypes to discover
 *       (default: {@code 10})</li>
 * </ul>
 *
 * <p>Run this test twice to verify that the second run is faster because
 * all {@link org.almostrealism.audio.data.WaveDetails} data is already
 * persisted on disk from the first run.</p>
 *
 * @see PrototypeDiscovery
 * @see OnnxAutoEncoder
 * @see ProtobufWaveDetailsStore
 */
public class OnnxPrototypeDiscoveryTest extends TestSuiteBase {

	private static final int SAMPLE_RATE = 44100;

	private static final String DEFAULT_SAMPLES_CONTAINER = "/samples";
	private static final String DEFAULT_SAMPLES_LOCAL =
			System.getProperty("user.home") + "/Music/Samples";
	private static final String DEFAULT_STORE =
			System.getProperty("user.home") + "/.ar/onnx-discovery-store";
	private static final String DEFAULT_MODELS_CONTAINER = "/models";
	private static final String DEFAULT_MODELS_LOCAL = "../models";
	private static final int DEFAULT_MAX_PROTOTYPES = 10;

	/**
	 * Runs prototype discovery using ONNX autoencoder for feature extraction.
	 *
	 * <p>On the first run, features are computed for all samples using the
	 * ONNX encoder model. On subsequent runs, features are loaded from the
	 * protobuf store, making discovery significantly faster.</p>
	 */
	@Test(timeout = 300000)
	public void discoverWithOnnxFeatures() throws Exception {
		String samplesPath = resolveSamplesPath();
		String storePath = System.getProperty("ar.test.store", DEFAULT_STORE);
		String modelsPath = resolveModelsPath();
		int maxPrototypes = Integer.getInteger("ar.test.maxPrototypes", DEFAULT_MAX_PROTOTYPES);

		File samplesDir = new File(samplesPath);
		File modelsDir = new File(modelsPath);
		File encoderFile = new File(modelsDir, "encoder.onnx");
		File decoderFile = new File(modelsDir, "decoder.onnx");
		File storeDir = new File(storePath);

		log("=== ONNX Prototype Discovery Test ===");
		log("Samples dir:  " + samplesDir.getAbsolutePath());
		log("Store dir:    " + storeDir.getAbsolutePath());
		log("Models dir:   " + modelsDir.getAbsolutePath());
		log("Encoder:      " + encoderFile.getAbsolutePath() + " (exists: " + encoderFile.exists() + ")");
		log("Decoder:      " + decoderFile.getAbsolutePath() + " (exists: " + decoderFile.exists() + ")");
		log("Max prototypes: " + maxPrototypes);
		log("");

		// Validate prerequisites
		if (!samplesDir.isDirectory()) {
			log("ERROR: Samples directory not found: " + samplesDir.getAbsolutePath());
			Assert.fail("Samples directory required: " + samplesDir.getAbsolutePath());
		}
		if (!encoderFile.exists()) {
			log("ERROR: ONNX encoder model not found: " + encoderFile.getAbsolutePath());
			Assert.fail("ONNX encoder model required: " + encoderFile.getAbsolutePath());
		}
		if (!decoderFile.exists()) {
			log("ERROR: ONNX decoder model not found: " + decoderFile.getAbsolutePath());
			Assert.fail("ONNX decoder model required: " + decoderFile.getAbsolutePath());
		}

		// Check store state before we start
		long storeInitialBytes = getDirectorySize(storeDir);
		log("Store exists: " + storeDir.exists());
		log("Store initial size: " + formatBytes(storeInitialBytes));
		log("");

		long startTotal = System.currentTimeMillis();

		// Initialize ONNX autoencoder
		log("Loading ONNX autoencoder...");
		long startOnnx = System.currentTimeMillis();
		OnnxAutoEncoder autoencoder = createAutoEncoder(encoderFile, decoderFile);
		long onnxMs = System.currentTimeMillis() - startOnnx;
		log("ONNX autoencoder loaded in " + onnxMs + " ms");

		AutoEncoderFeatureProvider featureProvider = new AutoEncoderFeatureProvider(autoencoder);

		// Initialize store and library
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir);
		int initialStoreEntries = store.size();
		log("Store loaded with " + initialStoreEntries + " existing entries");

		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir),
				SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(featureProvider);

		// Refresh library (compute features for new samples)
		long startRefresh = System.currentTimeMillis();
		log("");
		log("Starting library refresh (ONNX feature computation)...");
		library.refresh().join();
		long refreshMs = System.currentTimeMillis() - startRefresh;

		// Compute statistics
		Set<String> allIdentifiers = library.getAllIdentifiers();
		int totalSamples = allIdentifiers.size();
		int finalStoreEntries = store.size();
		int newEntriesComputed = finalStoreEntries - initialStoreEntries;
		long storeFinalBytes = getDirectorySize(storeDir);

		log("");
		log("=== Refresh Statistics ===");
		log("Total samples in library: " + totalSamples);
		log("Store entries before:     " + initialStoreEntries);
		log("Store entries after:      " + finalStoreEntries);
		log("New entries computed:     " + newEntriesComputed);
		log("Refresh time:             " + refreshMs + " ms");
		if (newEntriesComputed > 0) {
			double avgTimePerSample = (double) refreshMs / newEntriesComputed;
			log(String.format("Avg time per new sample:  %.1f ms%n", avgTimePerSample));
		} else {
			log("Avg time per new sample:  N/A (all loaded from cache)");
		}
		log("Store size on disk:       " + formatBytes(storeFinalBytes));
		log("");

		// Sanity check
		Assert.assertTrue("Expected at least 1 sample in library", totalSamples > 0);
		Assert.assertTrue("Expected store to have entries", finalStoreEntries > 0);

		// Run prototype discovery
		long startDiscovery = System.currentTimeMillis();
		log("Running prototype discovery...");
		List<PrototypeDiscovery.PrototypeResult> prototypes =
				PrototypeDiscovery.discoverPrototypes(library, maxPrototypes, System.out::println);
		long discoveryMs = System.currentTimeMillis() - startDiscovery;
		log("");

		// Print discovered prototypes
		log("=== Discovered Prototypes ===");
		log("Found " + prototypes.size() + " prototypes:");
		log("");
		for (int i = 0; i < prototypes.size(); i++) {
			PrototypeDiscovery.PrototypeResult prototype = prototypes.get(i);
			log(String.format("Prototype %d:%n", i + 1));
			log(String.format("  Identifier:  %s%n", prototype.identifier()));
			log(String.format("  Community:   %d samples%n", prototype.communitySize()));
			log(String.format("  Centrality:  %.6f%n", prototype.centrality()));
			log("");
		}

		// Cleanup
		library.stop();
		autoencoder.destroy();
		store.flush();
		store.close();

		long totalMs = System.currentTimeMillis() - startTotal;

		// Final summary
		log("=== Final Summary ===");
		log("ONNX model load:      " + onnxMs + " ms");
		log("Library refresh:      " + refreshMs + " ms");
		log("Prototype discovery:  " + discoveryMs + " ms");
		log("Total time:           " + totalMs + " ms");
		log("Samples processed:    " + totalSamples);
		log("Prototypes found:     " + prototypes.size());
		log("Store size on disk:   " + formatBytes(storeFinalBytes));
		log("");
		log("Run this test again - second run should be significantly faster");
		log("because features are loaded from the protobuf store.");

		// Verify we found prototypes
		Assert.assertTrue("Expected at least 1 prototype", prototypes.size() > 0);
	}

	/**
	 * Creates the ONNX autoencoder from the specified model files.
	 */
	private OnnxAutoEncoder createAutoEncoder(File encoderFile, File decoderFile)
			throws OrtException {
		return new OnnxAutoEncoder(
				encoderFile.getAbsolutePath(),
				decoderFile.getAbsolutePath());
	}

	/**
	 * Resolves the samples path, preferring container path if it exists.
	 */
	private String resolveSamplesPath() {
		String explicit = System.getProperty("ar.test.samples");
		if (explicit != null) {
			return explicit;
		}

		File containerPath = new File(DEFAULT_SAMPLES_CONTAINER);
		if (containerPath.isDirectory()) {
			return DEFAULT_SAMPLES_CONTAINER;
		}

		return DEFAULT_SAMPLES_LOCAL;
	}

	/**
	 * Resolves the models path, preferring container path if it exists.
	 */
	private String resolveModelsPath() {
		String explicit = System.getProperty("ar.test.models");
		if (explicit != null) {
			return explicit;
		}

		File containerPath = new File(DEFAULT_MODELS_CONTAINER);
		if (containerPath.isDirectory()) {
			return DEFAULT_MODELS_CONTAINER;
		}

		return DEFAULT_MODELS_LOCAL;
	}

	/**
	 * Calculates the total size of a directory in bytes.
	 */
	private long getDirectorySize(File dir) {
		if (!dir.exists() || !dir.isDirectory()) {
			return 0;
		}

		long size = 0;
		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isFile()) {
					size += file.length();
				} else if (file.isDirectory()) {
					size += getDirectorySize(file);
				}
			}
		}
		return size;
	}

	/**
	 * Formats a byte count as a human-readable string.
	 */
	private String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		} else if (bytes < 1024 * 1024) {
			return String.format("%.1f KB", bytes / 1024.0);
		} else if (bytes < 1024 * 1024 * 1024) {
			return String.format("%.1f MB", bytes / (1024.0 * 1024));
		} else {
			return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
		}
	}
}
