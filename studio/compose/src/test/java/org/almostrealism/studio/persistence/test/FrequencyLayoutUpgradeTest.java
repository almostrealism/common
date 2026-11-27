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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.AudioLibraryPersistence;
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
 * A library whose stereo records were analysed by a build that already had the current
 * feature model but not the per-channel frequency layout keeps those records' features
 * yet repairs their frequency data: the records carry no layout version, the store's
 * validated-model marker records none, and the next refresh recomputes their frequency
 * data from the files without encoding them again.
 */
public class FrequencyLayoutUpgradeTest extends TestSuiteBase {

	/** Sample rate of the library's files. */
	private static final int SAMPLE_RATE = 44100;

	/** Duration of the stereo file, in seconds. */
	private static final double DURATION = 0.5;

	/** Frames of the feature model's output. */
	private static final int FEATURE_FRAMES = 16;

	/** Bins of the feature model's output. */
	private static final int FEATURE_BINS = 32;

	/** The feature model's identifier. */
	private static final String MODEL = "current-model";

	/** Root of the temporary directories. */
	private Path tempDir;

	/** Directory of the library's sample files. */
	private Path samplesDir;

	/** Directory of the library store. */
	private Path storeDir;

	/** A provider for the current model that counts how many clips it encodes. */
	private static class CountingProvider extends LogSpectrumFeatureProvider {
		/** Number of clips encoded. */
		private final AtomicInteger encoded = new AtomicInteger();

		/** Creates the provider. */
		CountingProvider() {
			super(FEATURE_FRAMES, FEATURE_BINS, SAMPLE_RATE, DURATION, MODEL);
		}

		@Override
		public PackedCollection computeFeatures(WaveData waveData) {
			encoded.incrementAndGet();
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
		tempDir = Files.createTempDirectory("frequency-layout-upgrade-test");
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
	 * A stereo record with current features but frequency data in the old layout is
	 * repaired by the next refresh, without being encoded again, and the store is then
	 * validated under the current layout.
	 *
	 * @throws Exception if the library fails
	 */
	@Test(timeout = 300000)
	public void stereoRecordInTheOldLayoutIsRepairedWithoutReencoding() throws Exception {
		SineWaveFiles.generateLeftToneStereo(samplesDir, "stereo.wav", SAMPLE_RATE, DURATION, 440.0);
		List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		CountingProvider first = new CountingProvider();
		AudioLibrary library = openLibrary(store, first, errors);
		library.refresh().get();
		Assert.assertEquals("analysis must not fail: " + errors, 0, errors.size());
		Assert.assertEquals("the stereo file is encoded once", 1, first.encoded.get());
		Assert.assertEquals(MODEL, store.getValidatedModel());

		String id = store.allIdentifiers().iterator().next();
		WaveDetails analysed = store.get(id);
		Assert.assertEquals(2, analysed.getFreqChannelCount());
		Assert.assertEquals(WaveDetails.FREQUENCY_LAYOUT_VERSION, analysed.getFreqLayoutVersion());
		Assert.assertTrue("the left channel carries the tone", leftEnergy(analysed) > 1e-6);

		// Rewrite the record as the earlier build left it: features kept, frequency data
		// silent and unversioned, and a marker naming the model but no layout.
		analysed.setFreqData(new PackedCollection(2, analysed.getFreqFrameCount(),
				analysed.getFreqBinCount(), 1));
		analysed.setFreqLayoutVersion(0);
		store.put(id, analysed);
		library.stop();
		store.close();
		Files.writeString(storeDir.resolve("complete.model"), MODEL);

		ProtobufWaveDetailsStore reopened = new ProtobufWaveDetailsStore(storeDir.toFile());
		Assert.assertNull("a marker with no layout vouches for nothing", reopened.getValidatedModel());

		CountingProvider second = new CountingProvider();
		AudioLibrary relaunched = openLibrary(reopened, second, errors);
		relaunched.refresh().get();

		Assert.assertEquals("the relaunch must not fail: " + errors, 0, errors.size());
		Assert.assertEquals("the repair does not encode again", 0, second.encoded.get());
		WaveDetails repaired = reopened.get(id);
		Assert.assertEquals(WaveDetails.FREQUENCY_LAYOUT_VERSION, repaired.getFreqLayoutVersion());
		Assert.assertTrue("the left channel's tone is restored", leftEnergy(repaired) > 1e-6);
		Assert.assertEquals("the store is validated again", MODEL, reopened.getValidatedModel());

		relaunched.stop();
		reopened.close();
	}

	/**
	 * A validated-model marker written under a layout other than the one this build
	 * understands vouches for nothing. An unknown newer layout, which this build cannot
	 * read, is rejected exactly as an older one is, so the next refresh rereads every
	 * record rather than trusting a store a future build wrote.
	 *
	 * @throws IOException if the marker cannot be written
	 */
	@Test(timeout = 60000)
	public void aMarkerUnderAnUnknownLayoutVouchesForNothing() throws IOException {
		Files.createDirectories(storeDir);

		Files.writeString(storeDir.resolve("complete.model"),
				MODEL + "\nfrequency-layout=" + WaveDetails.FREQUENCY_LAYOUT_VERSION);
		ProtobufWaveDetailsStore current = new ProtobufWaveDetailsStore(storeDir.toFile());
		Assert.assertEquals("a marker under the current layout is honoured", MODEL,
				current.getValidatedModel());
		current.close();

		Files.writeString(storeDir.resolve("complete.model"),
				MODEL + "\nfrequency-layout=" + (WaveDetails.FREQUENCY_LAYOUT_VERSION + 1));
		ProtobufWaveDetailsStore future = new ProtobufWaveDetailsStore(storeDir.toFile());
		Assert.assertNull("a marker under an unknown newer layout is rejected",
				future.getValidatedModel());
		future.close();
	}

	/**
	 * A marker whose layout line is not a number vouches for nothing: the store degrades
	 * to a rescan rather than letting the unreadable value abort the library open.
	 *
	 * @throws IOException if the marker cannot be written
	 */
	@Test(timeout = 60000)
	public void aMarkerWithAnUnreadableLayoutVouchesForNothing() throws IOException {
		Files.createDirectories(storeDir);

		Files.writeString(storeDir.resolve("complete.model"),
				MODEL + "\nfrequency-layout=not-a-number");
		ProtobufWaveDetailsStore store = new ProtobufWaveDetailsStore(storeDir.toFile());
		Assert.assertNull("a marker with an unreadable layout is rejected",
				store.getValidatedModel());
		store.close();
	}

	/** The layout version survives encoding a record and decoding it again. */
	@Test(timeout = 60000)
	public void layoutVersionRoundTrips() {
		WaveDetails details = new WaveDetails("versioned", SAMPLE_RATE);
		details.setFreqChannelCount(2);
		details.setFreqLayoutVersion(WaveDetails.FREQUENCY_LAYOUT_VERSION);

		WaveDetails decoded = AudioLibraryPersistence.decode(
				AudioLibraryPersistence.encode(details, true), false);
		Assert.assertEquals(WaveDetails.FREQUENCY_LAYOUT_VERSION, decoded.getFreqLayoutVersion());
		Assert.assertTrue(decoded.isFrequencyLayoutCurrent());
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
	 * Sums the magnitudes of the first channel's frequency data.
	 *
	 * @param details the analysed record
	 * @return the total magnitude in channel 0
	 */
	private static double leftEnergy(WaveDetails details) {
		int length = details.getFreqFrameCount() * details.getFreqBinCount();
		double[] left = details.getFreqData(0).toArray(0, length);
		double energy = 0;
		for (double value : left) energy += Math.abs(value);
		return energy;
	}
}
