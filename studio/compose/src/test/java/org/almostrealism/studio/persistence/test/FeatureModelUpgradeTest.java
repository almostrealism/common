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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Upgrades a library store written the way a previous release wrote it to a
 * feature model with a different feature width, as happens when the configured
 * feature provider is replaced by one whose embeddings have a wider feature vector.
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

	/** A store that counts how many records are read back from it. */
	private static class CountingStore extends ProtobufWaveDetailsStore {
		/** Number of {@link #get(String)} calls. */
		private final AtomicInteger reads = new AtomicInteger();

		/** Opens a counting store at the given directory. */
		CountingStore(File rootDir) {
			super(rootDir);
		}

		@Override
		public WaveDetails get(String identifier) {
			reads.incrementAndGet();
			return super.get(identifier);
		}
	}

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

	/** A store whose validated-model marker can never be recorded. */
	private static class UnrecordableMarkerStore extends ProtobufWaveDetailsStore {
		/** Opens a store at the given directory. */
		UnrecordableMarkerStore(File rootDir) {
			super(rootDir);
		}

		@Override
		public void setValidatedModel(String modelIdentifier, Predicate<Set<String>> complete) {
			throw new UncheckedIOException(new IOException("simulated marker failure"));
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
	 * The first launch records the model it validated the store against, so a
	 * relaunch under the same model skips the per-record completeness scan rather
	 * than reading every item back from the store. A different model still forces
	 * the scan, so the skip is not vacuous.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void relaunchUnderTheSameModelDoesNotRescanTheStore() throws Exception {
		int count = 8;
		generateSineWaves(count);

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, new CountingProvider(), errors);
		library.refresh().get();
		Assert.assertEquals("analysis must not fail: " + errors, 0, errors.size());
		Assert.assertEquals(count, library.allDetails().count());
		library.stop();
		store.close();

		CountingStore reopened = new CountingStore(storeDir.toFile());
		AudioLibrary relaunched = openLibrary(reopened, new CountingProvider(), errors);
		int readsBeforeRefresh = reopened.reads.get();
		relaunched.refresh().get();

		Assert.assertEquals("a same-model relaunch reads no records back from the store",
				readsBeforeRefresh, reopened.reads.get());
		Assert.assertEquals("the relaunch must not fail: " + errors, 0, errors.size());
		Assert.assertEquals("every item is still complete", count, relaunched.allDetails().count());
		relaunched.stop();
		reopened.close();

		CountingStore underNewModel = new CountingStore(storeDir.toFile());
		AudioLibrary otherModel = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()), SAMPLE_RATE, underNewModel);
		otherModel.getWaveDetailsFactory().setFeatureProvider(new LogSpectrumFeatureProvider(
				FEATURE_FRAMES, NEW_BINS, SAMPLE_RATE, SAMPLE_DURATION, "another-model"));
		otherModel.setErrorListener(errors::add);
		int readsBeforeOtherRefresh = underNewModel.reads.get();
		otherModel.refresh().get();

		Assert.assertTrue("a changed model rescans the store",
				underNewModel.reads.get() > readsBeforeOtherRefresh);
		otherModel.stop();
		underNewModel.close();
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

	/**
	 * Persisting an item whose active model identifier is stale indexes its
	 * current-model vector under the current model: the store's index model is not
	 * switched back to the stale model, so the index built for the current model
	 * keeps every item.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void persistIndexesUnderTheCurrentModelDespiteAStaleActiveModel() throws Exception {
		int count = 4;
		generateSineWaves(count);

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, new CountingProvider(), errors);
		library.refresh().get();
		Assert.assertEquals("analysis must not fail: " + errors, 0, errors.size());
		Assert.assertEquals(count, store.indexedEmbeddingCount());

		WaveDetails details = store.get(store.allIdentifiers().iterator().next());
		Assert.assertNotNull(details.getFeatures(NEW_MODEL));
		details.setActiveModelIdentifier("stale-model");
		library.persist(details);

		Assert.assertEquals("the current model's index keeps every item",
				count, store.indexedEmbeddingCount());
		Assert.assertEquals("the persisted item is indexed for the current model",
				NEW_MODEL, details.getActiveModelIdentifier());

		details.setActiveModelIdentifier(null);
		library.persist(details);
		Assert.assertEquals(NEW_MODEL, details.getActiveModelIdentifier());
		Assert.assertEquals("a null active model also leaves the index intact",
				count, store.indexedEmbeddingCount());

		library.stop();
		store.close();
	}

	/**
	 * Including an incomplete record after a fully validated refresh drops the
	 * validated-model marker, so the next launch rescans the store rather than
	 * restoring the incomplete record as complete and trusting it forever.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void includingAnIncompleteRecordClearsTheValidatedModelMarker() throws Exception {
		int count = 6;
		generateSineWaves(count);

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, new CountingProvider(), errors);
		library.refresh().get();
		Assert.assertEquals("analysis must not fail: " + errors, 0, errors.size());
		Assert.assertEquals("a fully validated refresh records the model",
				NEW_MODEL, store.getValidatedModel());

		// No features for the model: the record is incomplete, so the store is no
		// longer fully validated once it holds it.
		WaveDetails incomplete = new WaveDetails("incomplete-no-features", SAMPLE_RATE);
		incomplete.setPersistent(true);
		library.include(incomplete);
		Assert.assertNull("including an incomplete record clears the validated-model marker",
				store.getValidatedModel());

		library.stop();
		store.close();

		CountingStore reopened = new CountingStore(storeDir.toFile());
		AudioLibrary relaunched = openLibrary(reopened, new CountingProvider(), errors);
		int readsBeforeRefresh = reopened.reads.get();
		relaunched.refresh().get();

		Assert.assertTrue("the next launch rescans rather than trusting the incomplete record",
				reopened.reads.get() > readsBeforeRefresh);
		Assert.assertEquals("the relaunch must not fail: " + errors, 0, errors.size());
		relaunched.stop();
		reopened.close();
	}

	/**
	 * A stored record that cannot be recomputed for the current model — no file in
	 * the tree and no stored audio or frequency data to synthesize from — leaves the
	 * store unvalidated, so its marker is never written and the next launch rescans.
	 * Without this, the record would be restored as complete and trusted forever.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void anUnrecomputableStoredRecordLeavesTheStoreUnvalidated() throws Exception {
		ProtobufWaveDetailsStore seed = new ProtobufWaveDetailsStore(storeDir.toFile());
		WaveDetails orphan = new WaveDetails("orphan-unrecomputable", SAMPLE_RATE);
		orphan.setPersistent(true);
		seed.put("orphan-unrecomputable", orphan);
		seed.close();

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		CountingStore store = new CountingStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, new CountingProvider(), errors);
		library.refresh().get();

		Assert.assertEquals("the refresh must not fail: " + errors, 0, errors.size());
		Assert.assertNull("a record that cannot be completed must not mark the store validated",
				store.getValidatedModel());

		library.stop();
		store.close();

		CountingStore reopened = new CountingStore(storeDir.toFile());
		AudioLibrary relaunched = openLibrary(reopened, new CountingProvider(), errors);
		int readsBeforeRefresh = reopened.reads.get();
		relaunched.refresh().get();

		Assert.assertTrue("the next launch rescans because the store was never marked validated",
				reopened.reads.get() > readsBeforeRefresh);
		relaunched.stop();
		reopened.close();
	}

	/**
	 * The validated-model marker is written only after the recomputed records are
	 * flushed to disk, so a second store opened on the same directory sees every
	 * record the marker vouches for. Were the marker written first, a crash in the
	 * gap would leave the next launch trusting records that were never persisted.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void aValidatedRefreshFlushesRecordsBeforeRecordingTheModel() throws Exception {
		int count = 5;
		generateSineWaves(count);

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, new CountingProvider(), errors);
		library.refresh().get();
		Assert.assertEquals("analysis must not fail: " + errors, 0, errors.size());
		Assert.assertEquals("a fully validated refresh records the model",
				NEW_MODEL, store.getValidatedModel());

		ProtobufWaveDetailsStore observer = new ProtobufWaveDetailsStore(storeDir.toFile());
		Assert.assertEquals("records are flushed to disk before the model is recorded",
				count, observer.size());
		observer.close();

		library.stop();
		store.close();
	}

	/**
	 * A record written to the store after a validated refresh by a path other than
	 * {@link AudioLibrary#include} — a direct store write, as the migration path and
	 * {@link AudioLibrary#persist} make — clears the marker, so no write can leave
	 * a marker vouching for a record it never tested.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void anyStoreWriteAfterValidationClearsTheMarker() throws Exception {
		generateSineWaves(3);

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, new CountingProvider(), errors);
		library.refresh().get();
		Assert.assertEquals("analysis must not fail: " + errors, 0, errors.size());
		Assert.assertEquals(NEW_MODEL, store.getValidatedModel());

		store.put("written-directly", new WaveDetails("written-directly", SAMPLE_RATE));
		Assert.assertNull("a direct store write clears the marker", store.getValidatedModel());

		library.refresh().get();
		Assert.assertNull("an unrecomputable record keeps the store unvalidated", store.getValidatedModel());

		library.remove("written-directly");
		library.refresh().get();
		Assert.assertEquals("once every record is complete again the model is recorded",
				NEW_MODEL, store.getValidatedModel());

		WaveDetails complete = store.get(store.allIdentifiers().iterator().next());
		library.persist(complete);
		Assert.assertNull("persist clears the marker before writing, even for a complete record",
				store.getValidatedModel());

		library.stop();
		store.close();
	}

	/**
	 * Replacing a complete record with an incomplete one through
	 * {@link AudioLibrary#include} drops it from the complete set in the same
	 * process, so the next same-model refresh recomputes it rather than trusting it.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void includingAnIncompleteReplacementDropsTheIdentifierFromTheCompleteSet() throws Exception {
		generateSineWaves(3);

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		CountingProvider provider = new CountingProvider();
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, provider, errors);
		library.refresh().get();
		Assert.assertEquals("analysis must not fail: " + errors, 0, errors.size());

		String id = store.allIdentifiers().iterator().next();
		Assert.assertTrue(library.getAllIdentifiers().contains(id));

		WaveDetails stored = store.get(id);
		WaveDetails replacement = new WaveDetails(id, SAMPLE_RATE);
		replacement.setFreqData(stored.getFreqData());
		replacement.setFreqSampleRate(stored.getFreqSampleRate());
		replacement.setFreqChannelCount(stored.getFreqChannelCount());
		replacement.setFreqBinCount(stored.getFreqBinCount());
		replacement.setFreqFrameCount(stored.getFreqFrameCount());
		library.include(replacement);
		Assert.assertFalse("an incomplete replacement is no longer trusted as complete",
				library.getAllIdentifiers().contains(id));

		int computedBefore = provider.computed.get();
		library.refresh().get();
		Assert.assertEquals("the replaced record is recomputed by the next refresh",
				computedBefore + 1, provider.computed.get());
		Assert.assertNotNull(store.get(id).getFeatures(NEW_MODEL));
		Assert.assertEquals(NEW_MODEL, store.getValidatedModel());

		library.stop();
		store.close();
	}

	/**
	 * A marker that cannot be cleared fails the write that would invalidate it,
	 * rather than surviving to let the next launch trust a record it never tested.
	 *
	 * @throws Exception if the store cannot be prepared
	 */
	@Test(timeout = 60000)
	public void aMarkerThatCannotBeClearedFailsTheWrite() throws Exception {
		// A non-empty directory in the marker's place cannot be deleted.
		Path marker = storeDir.resolve("complete.model");
		Files.createDirectories(marker);
		Files.writeString(marker.resolve("blocker"), "x");

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		try {
			store.put("record", new WaveDetails("record", SAMPLE_RATE));
			Assert.fail("a write that cannot clear the marker must fail");
		} catch (UncheckedIOException expected) {
			Assert.assertFalse("the record is not written while the marker stands",
					store.containsKey("record"));
		} finally {
			store.close();
		}
	}

	/**
	 * The marker is shared by every store on the directory, so a write through
	 * one store clears a marker another store published after the first opened.
	 */
	@Test(timeout = 60000)
	public void aWriteClearsAMarkerPublishedByAnotherStoreOnTheDirectory() {
		ProtobufWaveDetailsStore writer = new ProtobufWaveDetailsStore(storeDir.toFile());
		ProtobufWaveDetailsStore publisher = new ProtobufWaveDetailsStore(storeDir.toFile());

		publisher.setValidatedModel(NEW_MODEL, ids -> true);
		Assert.assertEquals(NEW_MODEL, writer.getValidatedModel());

		writer.put("record", new WaveDetails("record", SAMPLE_RATE));
		Assert.assertNull("a write must clear the shared marker", publisher.getValidatedModel());

		writer.close();
		publisher.close();
	}

	/**
	 * When recording the validated model fails, the refresh future completes
	 * exceptionally instead of never completing.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void aFailureToRecordTheModelFailsTheRefreshRatherThanHangingIt() throws Exception {
		generateSineWaves(2);

		ProtobufWaveDetailsStore store = new UnrecordableMarkerStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, new CountingProvider(), new ArrayList<>());

		try {
			library.refresh().get(120, TimeUnit.SECONDS);
			Assert.fail("the refresh must report the failure");
		} catch (ExecutionException expected) {
			Assert.assertTrue(expected.getCause() instanceof UncheckedIOException);
		} finally {
			library.stop();
			store.close();
		}
	}

	/** Generates the library's sample files. */
	private List<File> generateSineWaves(int count) throws IOException {
		return SineWaveFiles.generate(samplesDir, count, SAMPLE_RATE, SAMPLE_DURATION);
	}
}
