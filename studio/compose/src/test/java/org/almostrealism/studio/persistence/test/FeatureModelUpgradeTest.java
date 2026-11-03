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

package org.almostrealism.studio.persistence.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
import org.almostrealism.studio.persistence.test.support.LogSpectrumFeatureProvider;
import org.almostrealism.studio.persistence.test.support.SineWaveFiles;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Upgrades a library store written the way the previous release wrote it to a
 * feature model with a different feature width, as the Rings 0.40 to 0.41 upgrade
 * does (64-bin ONNX autoencoder features to 256-bin Stable Audio 3 features).
 *
 * <p>The previous release recorded features in the legacy unkeyed slot, with no
 * model identifier, and indexed every item in the store's HNSW index with the
 * mean-pooled feature vector, so its saved {@code hnsw.bin} has the old width.
 * The new model's vectors have another width; inserting them into that index used
 * to throw on every item, failing the whole re-analysis, every newly added sample
 * and the old-model cleanup. These tests write that old form, reopen it with the
 * new model, and require the upgrade to succeed.</p>
 */
public class FeatureModelUpgradeTest extends TestSuiteBase {

	/** Sample rate of the library's audio, in Hz. */
	private static final int SAMPLE_RATE = 44100;

	/** Duration of each sample, in seconds. */
	private static final double SAMPLE_DURATION = 0.25;

	/** Feature frames per sample, for both models. */
	private static final int FEATURE_FRAMES = 16;

	/** Feature width of the previous release's model. */
	private static final int OLD_BINS = 32;

	/** Feature width of the new model. */
	private static final int NEW_BINS = 64;

	/** Identifier of the new model. */
	private static final String NEW_MODEL = "new-model";

	/** Temporary directory holding the samples and the store. */
	private Path tempDir;

	/** Directory of the library's sample files. */
	private Path samplesDir;

	/** Directory of the library store. */
	private Path storeDir;

	/** A provider for the new model that counts how many clips it encodes. */
	private static class CountingProvider extends LogSpectrumFeatureProvider {
		/** Number of clips encoded. */
		private final AtomicInteger computed = new AtomicInteger();

		/** Creates the new model's provider. */
		CountingProvider() {
			super(FEATURE_FRAMES, NEW_BINS, SAMPLE_RATE, SAMPLE_DURATION, NEW_MODEL);
		}

		@Override
		public PackedCollection computeFeatures(WaveData waveData) {
			computed.incrementAndGet();
			return super.computeFeatures(waveData);
		}
	}

	/**
	 * Creates the temporary directories.
	 *
	 * @throws IOException if a directory cannot be created
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("feature-model-upgrade-test");
		samplesDir = tempDir.resolve("samples");
		storeDir = tempDir.resolve("store");
		Files.createDirectories(samplesDir);
	}

	/**
	 * Deletes the temporary directories.
	 *
	 * @throws IOException if the directories cannot be walked
	 */
	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}

	/**
	 * Writes the previous release's store: each item analysed with frequency data
	 * and legacy unkeyed features of the old width, and put with its mean-pooled
	 * embedding, so the saved HNSW index has the old width.
	 */
	private void writePreviousReleaseStore(List<File> wavFiles) {
		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);
		LogSpectrumFeatureProvider oldModel = new LogSpectrumFeatureProvider(
				FEATURE_FRAMES, OLD_BINS, SAMPLE_RATE, SAMPLE_DURATION);

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		for (File wav : wavFiles) {
			WaveDetails details = factory.forFile(wav.getAbsolutePath());
			PackedCollection features = oldModel.computeFeatures(details.getWaveData());
			details.setFeatureSampleRate(oldModel.getFeatureSampleRate());
			details.setFeatureChannelCount(1);
			details.setFeatureBinCount(features.getShape().length(1));
			details.setFeatureFrameCount(features.getShape().length(0));
			details.setFeatureData(features);

			store.put(details.getIdentifier(), details, details.meanPooledEmbedding());
		}

		Assert.assertEquals("the previous release indexed every item",
				wavFiles.size(), store.indexedEmbeddingCount());
		store.close();
	}

	/** Opens the store with a library analysing through the given provider. */
	private AudioLibrary openLibrary(ProtobufWaveDetailsStore store, CountingProvider provider,
									 List<Exception> errors) {
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()), SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(provider);
		library.setErrorListener(errors::add);
		return library;
	}

	/**
	 * Reopens a previous-release store with the new model: re-analysis, search,
	 * the cleanup and the next launch all succeed, and nothing is recomputed twice.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void previousReleaseLibraryUpgradesToAWiderModel() throws Exception {
		int count = 12;
		List<File> wavFiles = generateSineWaves(count);
		writePreviousReleaseStore(wavFiles);

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		CountingProvider provider = new CountingProvider();
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, provider, errors);
		library.refresh().get();

		Assert.assertEquals("re-analysis with the new model must not fail: " + errors,
				0, errors.size());

		List<WaveDetails> upgraded = library.allDetails().toList();
		Assert.assertEquals("every item is complete for the new model", count, upgraded.size());
		for (WaveDetails d : upgraded) {
			Assert.assertNotNull("new-model features computed", d.getFeatures(NEW_MODEL));
			Assert.assertNotNull("the previous release's features are kept for a downgrade",
					d.getLegacyFeatureData());
		}

		Assert.assertEquals("the index holds every item at the new model's width",
				count, store.indexedEmbeddingCount());
		PackedCollection query = upgraded.get(0).meanPooledEmbedding(library.currentModelId());
		Assert.assertEquals(NEW_BINS, query.getMemLength());
		Assert.assertFalse("search with a new-model vector finds neighbours",
				store.searchNeighbors(query, 5).isEmpty());
		Assert.assertTrue("an old-model query matches nothing rather than failing",
				store.searchNeighbors(new PackedCollection(OLD_BINS).fill(1.0), 5).isEmpty());

		Assert.assertEquals("the cleanup removes the old model's data from every item",
				count, library.otherModelFeatures().remove());
		Assert.assertEquals("the cleanup must not fail: " + errors, 0, errors.size());

		library.stop();
		store.close();

		CountingProvider relaunch = new CountingProvider();
		ProtobufWaveDetailsStore reopened = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary relaunched = openLibrary(reopened, relaunch, errors);
		relaunched.refresh().get();

		Assert.assertEquals("nothing is recomputed on the next launch", 0, relaunch.computed.get());
		Assert.assertEquals("every item is still complete", count, relaunched.allDetails().count());
		Assert.assertEquals("the index survives the relaunch", count, reopened.indexedEmbeddingCount());
		Assert.assertEquals("the relaunch must not fail: " + errors, 0, errors.size());

		relaunched.stop();
		reopened.close();
	}

	/**
	 * An item the new model has not yet analysed offers no embedding for the new
	 * model's index, so its old-width vector cannot displace the new index.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void itemsNotYetReanalysedKeepTheirOldVectorsOutOfTheNewIndex() throws Exception {
		List<File> wavFiles = generateSineWaves(4);
		writePreviousReleaseStore(wavFiles);

		CountingProvider provider = new CountingProvider();
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, provider, new ArrayList<>());

		WaveDetails legacyOnly = store.get(store.allIdentifiers().iterator().next());
		Assert.assertNotNull(legacyOnly.getLegacyFeatureData());
		Assert.assertNull("an item the new model has not analysed has no embedding to index",
				legacyOnly.meanPooledEmbedding(library.currentModelId()));

		library.stop();
		store.close();
	}

	/** Generates the library's sample files. */
	private List<File> generateSineWaves(int count) throws IOException {
		return SineWaveFiles.generate(samplesDir, count, SAMPLE_RATE, SAMPLE_DURATION);
	}
}
