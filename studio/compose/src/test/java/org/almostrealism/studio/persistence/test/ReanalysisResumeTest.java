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
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
import org.almostrealism.studio.persistence.test.support.LogSpectrumFeatureProvider;
import org.almostrealism.studio.persistence.test.support.PreviousReleaseStore;
import org.almostrealism.studio.persistence.test.support.SineWaveFiles;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The one-time re-analysis a library store written by the previous release goes
 * through when it is opened with a new feature model, exercised the way it happens
 * on a real first launch: interrupted part way and resumed on the next launch, and
 * over a library holding duplicate content, missing and unreadable files, and
 * samples added while the backlog is still being worked through.
 *
 * <p>The "kill" in these tests is the app's actual quit path: the process exits
 * without stopping the library or closing its store, so whatever the store has not
 * yet written to disk is lost. A periodic save (the app flushes the store every few
 * minutes while analysis runs) is simulated by flushing the store explicitly.</p>
 */
public class ReanalysisResumeTest extends TestSuiteBase {

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

	/** Longest a test waits for the analysis worker to reach a given point, in milliseconds. */
	private static final long WAIT_MILLIS = 120000;

	/** Temporary directory holding the samples and the store. */
	private Path tempDir;

	/** Directory of the library's sample files. */
	private Path samplesDir;

	/** Directory of the library store. */
	private Path storeDir;

	/**
	 * A new-model provider whose encodes each need a permit, so a test can stop the
	 * analysis worker part way through an encode, and can make every encode after a
	 * simulated quit fail as the dead process's would never complete. It records how
	 * many encodes started and the frame count of each clip, in order.
	 */
	private static class GatedProvider extends LogSpectrumFeatureProvider {
		/** Permits for encodes; an encode blocks until it takes one. */
		private final Semaphore permits;

		/** Number of encodes started. */
		private final AtomicInteger started = new AtomicInteger();

		/** Frame count of each clip encoded, in the order encodes started. */
		private final List<Integer> clipFrames = Collections.synchronizedList(new ArrayList<>());

		/** Set when the process is considered dead: encodes taking a permit then fail. */
		private volatile boolean dead;

		/**
		 * Creates a provider for the new model.
		 *
		 * @param permits the encodes allowed before the provider blocks
		 */
		GatedProvider(int permits) {
			super(FEATURE_FRAMES, NEW_BINS, SAMPLE_RATE, SAMPLE_DURATION, NEW_MODEL);
			this.permits = new Semaphore(permits);
		}

		@Override
		public PackedCollection computeFeatures(WaveData waveData) {
			started.incrementAndGet();
			clipFrames.add(waveData.getFrameCount());
			permits.acquireUninterruptibly();
			if (dead) throw new IllegalStateException("the process quit during this encode");
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
		tempDir = Files.createTempDirectory("reanalysis-resume-test");
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
	 * Quitting part way through the re-analysis keeps every item the store had
	 * written to disk before the quit, and the next launch resumes: it re-encodes
	 * only the items not on disk (including the one being encoded when the process
	 * quit, and one finished after the last save, which was still only in memory),
	 * never the saved ones, and finishes with every item complete and nothing
	 * corrupt.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void anInterruptedReanalysisResumesWithoutRecomputingSavedItems() throws Exception {
		int count = 8;
		int saved = 4;
		writePreviousReleaseStore(generateSineWaves(count, SAMPLE_DURATION));

		GatedProvider firstLaunchModel = new GatedProvider(saved);
		ProtobufWaveDetailsStore firstStore = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary firstLaunch = openLibrary(firstStore, firstLaunchModel, new ArrayList<>());
		CompletableFuture<Void> firstRefresh = firstLaunch.refresh();

		// Items 1..4 are encoded and persisted; item 5 is mid-encode. The app's
		// periodic save then writes what the store holds to disk.
		awaitCondition(() -> firstLaunchModel.started.get() == saved + 1);
		firstStore.flush();

		// Item 5 finishes after the save, so it is only in memory; item 6 is
		// mid-encode when the process quits without stopping or flushing anything.
		firstLaunchModel.permits.release();
		awaitCondition(() -> firstLaunchModel.started.get() == saved + 2);
		firstLaunchModel.dead = true;

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		GatedProvider relaunchModel = new GatedProvider(Integer.MAX_VALUE);
		ProtobufWaveDetailsStore relaunchStore = new ProtobufWaveDetailsStore(storeDir.toFile());

		long onDisk = relaunchStore.allIdentifiers().stream()
				.map(relaunchStore::get)
				.filter(d -> d != null && d.getFeatures(NEW_MODEL) != null)
				.count();
		Assert.assertEquals("exactly the items saved before the quit are on disk with new-model features",
				saved, onDisk);
		Assert.assertEquals("the quit leaves no record missing or unreadable",
				count, relaunchStore.allIdentifiers().stream().map(relaunchStore::get).filter(d -> d != null).count());

		AudioLibrary relaunch = openLibrary(relaunchStore, relaunchModel, errors);
		relaunch.refresh().get(WAIT_MILLIS, TimeUnit.MILLISECONDS);

		Assert.assertEquals("the relaunch must not fail: " + errors, 0, errors.size());
		Assert.assertEquals("only the items not saved before the quit are re-encoded",
				count - saved, relaunchModel.started.get());

		List<WaveDetails> upgraded = relaunch.allDetails().toList();
		Assert.assertEquals("every item is complete after the resumed re-analysis", count, upgraded.size());
		for (WaveDetails d : upgraded) {
			Assert.assertNotNull("new-model features computed", d.getFeatures(NEW_MODEL));
			Assert.assertNotNull("the previous release's features are kept", d.getLegacyFeatureData());
			Assert.assertNotNull("frequency data is intact", d.getFreqData());
		}
		Assert.assertEquals("the finished resume records the model, so the next launch does not rescan",
				NEW_MODEL, relaunchStore.getValidatedModel());

		relaunch.stop();
		relaunchStore.close();

		// Let the first launch's worker, which stands in for the dead process, drain
		// (every remaining encode fails) so nothing outlives the test.
		firstLaunchModel.permits.release(count);
		firstRefresh.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
	}

	/**
	 * The same audio under two paths is one library item: the re-analysis encodes
	 * it once, not once per path.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void duplicateContentUnderAnotherPathIsEncodedOnce() throws Exception {
		int count = 4;
		List<File> wavFiles = generateSineWaves(count, SAMPLE_DURATION);
		writePreviousReleaseStore(wavFiles);

		Path copies = Files.createDirectories(samplesDir.resolve("copies"));
		Files.copy(wavFiles.get(0).toPath(), copies.resolve("same-audio.wav"));
		Files.copy(wavFiles.get(1).toPath(), copies.resolve("also-the-same.wav"));

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		GatedProvider provider = new GatedProvider(Integer.MAX_VALUE);
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, provider, errors);
		library.refresh().get(WAIT_MILLIS, TimeUnit.MILLISECONDS);

		Assert.assertEquals("the re-analysis must not fail: " + errors, 0, errors.size());
		Assert.assertEquals("each distinct audio content is encoded once", count, provider.started.get());
		Assert.assertEquals("every item is complete", count, library.allDetails().count());

		library.stop();
		store.close();
	}

	/**
	 * A library whose files have changed since the previous release analysed it —
	 * one deleted, one now unreadable (its header promises audio the file no longer
	 * holds) — neither stalls the re-analysis nor makes it repeat: the refresh
	 * finishes, every readable item completes, and the next launch encodes nothing.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void missingAndUnreadableFilesNeitherStallNorRepeatTheReanalysis() throws Exception {
		int count = 5;
		List<File> wavFiles = generateSineWaves(count, SAMPLE_DURATION);
		writePreviousReleaseStore(wavFiles);

		Files.delete(wavFiles.get(0).toPath());
		try (RandomAccessFile truncated = new RandomAccessFile(wavFiles.get(1), "rw")) {
			truncated.setLength(64);
		}

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		GatedProvider provider = new GatedProvider(Integer.MAX_VALUE);
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, provider, errors);
		library.refresh().get(WAIT_MILLIS, TimeUnit.MILLISECONDS);

		log("First launch: " + provider.started.get() + " encode(s), " + errors.size() +
				" reported error(s), " + library.allDetails().count() + " complete item(s), validated model " +
				store.getValidatedModel() + ", errors " + errors);

		for (File readable : wavFiles.subList(2, count)) {
			Assert.assertTrue("every readable file completes for the new model: " + readable,
					library.getAllIdentifiers().contains(
							new FileWaveDataProvider(readable.getPath()).getIdentifier()));
		}

		library.stop();
		store.close();

		List<Exception> relaunchErrors = Collections.synchronizedList(new ArrayList<>());
		GatedProvider relaunchModel = new GatedProvider(Integer.MAX_VALUE);
		ProtobufWaveDetailsStore reopened = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary relaunch = openLibrary(reopened, relaunchModel, relaunchErrors);
		relaunch.refresh().get(WAIT_MILLIS, TimeUnit.MILLISECONDS);

		log("Relaunch: " + relaunchModel.started.get() + " encode(s), " + relaunchErrors.size() +
				" reported error(s), validated model " + reopened.getValidatedModel());
		Assert.assertEquals("the next launch encodes nothing again", 0, relaunchModel.started.get());

		relaunch.stop();
		reopened.close();
	}

	/**
	 * A sample added while the backlog is still being re-analysed (as the app's
	 * import does, requesting the new file's details) is encoded next, ahead of the
	 * remaining backlog, rather than waiting for the whole library.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void aSampleAddedDuringTheReanalysisIsEncodedAheadOfTheBacklog() throws Exception {
		int count = 6;
		writePreviousReleaseStore(generateSineWaves(count, SAMPLE_DURATION));

		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
		GatedProvider provider = new GatedProvider(1);
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		AudioLibrary library = openLibrary(store, provider, errors);
		CompletableFuture<Void> refresh = library.refresh();

		// The worker is mid-encode on the second backlog item when the sample is added.
		awaitCondition(() -> provider.started.get() == 2);
		Path added = Files.createDirectories(samplesDir.resolve("added"));
		File addedFile = SineWaveFiles.generate(added, 1, SAMPLE_RATE, 2 * SAMPLE_DURATION).get(0);
		library.getDetailsForFileNow(addedFile.getPath(), true);

		provider.permits.release(Integer.MAX_VALUE / 2);
		refresh.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
		WaveDetails addedDetails = library.getDetailsAwait(
				new FileWaveDataProvider(addedFile.getPath()), 60);

		Assert.assertEquals("the re-analysis must not fail: " + errors, 0, errors.size());
		Assert.assertNotNull("the added sample is analysed", addedDetails.getFeatures(NEW_MODEL));

		int addedFrames = (int) (2 * SAMPLE_DURATION * SAMPLE_RATE);
		Assert.assertEquals("the added sample is encoded right after the in-flight item: " + provider.clipFrames,
				2, provider.clipFrames.indexOf(addedFrames));
		Assert.assertEquals("every backlog item and the added sample complete",
				count + 1, library.allDetails().count());

		library.stop();
		store.close();
	}

	/** Writes the previous release's store for the given files. */
	private void writePreviousReleaseStore(List<File> wavFiles) {
		Assert.assertEquals("the previous release indexed every item", wavFiles.size(),
				PreviousReleaseStore.write(storeDir, wavFiles, SAMPLE_RATE,
						FEATURE_FRAMES, OLD_BINS, SAMPLE_DURATION));
	}

	/** Opens the store with a library analysing through the given provider. */
	private AudioLibrary openLibrary(ProtobufWaveDetailsStore store, GatedProvider provider,
									 List<Exception> errors) {
		AudioLibrary library = new AudioLibrary(
				new FileWaveDataProviderNode(samplesDir.toFile()), SAMPLE_RATE, store);
		library.getWaveDetailsFactory().setFeatureProvider(provider);
		library.setErrorListener(errors::add);
		return library;
	}

	/** Generates the library's sample files. */
	private List<File> generateSineWaves(int count, double duration) throws IOException {
		return SineWaveFiles.generate(samplesDir, count, SAMPLE_RATE, duration);
	}

	/**
	 * Waits until the condition holds.
	 *
	 * @throws InterruptedException if interrupted while waiting
	 * @throws AssertionError       if it does not hold within {@link #WAIT_MILLIS}
	 */
	private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MILLIS;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) Assert.fail("the analysis worker did not reach the expected point");
			Thread.sleep(20);
		}
	}
}
