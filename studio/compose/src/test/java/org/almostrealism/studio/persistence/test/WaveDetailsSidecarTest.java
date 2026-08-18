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

import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.protobuf.Collections;
import org.almostrealism.studio.persistence.AudioLibraryPersistence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Verifies that a {@link WaveDetails} file written beside the audio it
 * describes neither stores nor loads a second copy of that audio.
 *
 * <p>These files are written next to a rendered WAV and read back to display
 * it, so the samples they once embedded were a duplicate of a file already on
 * disk. Loading one produced a collection that reads straight out of the
 * parsed message rather than copying from it, which meant every loaded detail
 * pinned an entire recording on the heap for as long as it was held — enough,
 * across a population of generated arrangements, to exhaust it.</p>
 *
 * <p>Both halves are covered because they fail differently: a writer that
 * still embeds audio doubles the size of every file it produces, while a
 * reader that still decodes it keeps the old files expensive forever.</p>
 */
public class WaveDetailsSidecarTest extends TestSuiteBase {

	/** Sample rate for the audio under test. */
	private static final int SAMPLE_RATE = 44100;

	/** Length of the raw audio under test, large enough to dominate the file. */
	private static final int FRAMES = 16384;

	/** Directory holding the files written by each test. */
	private File tempDir;

	/**
	 * Creates the directory each test writes into.
	 *
	 * @throws IOException if the directory cannot be created
	 */
	@Before
	public void createTempDirectory() throws IOException {
		tempDir = Files.createTempDirectory("wave-details-sidecar-test").toFile();
	}

	/** Removes the directory and everything written into it. */
	@After
	public void deleteTempDirectory() {
		if (tempDir == null) return;

		File[] contents = tempDir.listFiles();
		if (contents != null) {
			for (File f : contents) {
				f.delete();
			}
		}

		tempDir.delete();
	}

	/**
	 * Creates details carrying raw audio alongside the analysis derived from
	 * it, as they arrive from the factory once a render completes.
	 */
	private WaveDetails detailsWithAudio(String identifier) {
		WaveDetails details = new WaveDetails(identifier, SAMPLE_RATE);
		details.setChannelCount(1);
		details.setFrameCount(FRAMES);
		details.setData(new PackedCollection(FRAMES));

		details.setFreqSampleRate(100.0);
		details.setFreqChannelCount(1);
		details.setFreqBinCount(4);
		details.setFreqFrameCount(2);
		details.setFreqData(new PackedCollection(2, 4));

		details.setFeatureSampleRate(100.0);
		details.setFeatureChannelCount(1);
		details.setFeatureBinCount(2);
		details.setFeatureFrameCount(2);
		details.setFeatureData(new PackedCollection(2, 2));
		return details;
	}

	/** Reads back the message a saved file actually contains. */
	private Audio.WaveDetailData read(File f) throws IOException {
		try (FileInputStream in = new FileInputStream(f)) {
			return Audio.WaveDetailData.newBuilder().mergeFrom(in).build();
		}
	}

	/**
	 * A saved file carries the analysis but not the audio it sits beside.
	 */
	@Test(timeout = 30000)
	public void savedDetailsOmitTheAudioTheySitBeside() throws IOException {
		AudioLibraryPersistence.saveWaveDetails(
				detailsWithAudio("saved-details"), tempDir.getPath());

		Audio.WaveDetailData written = read(new File(tempDir, "saved-details.bin"));

		Assert.assertFalse("The audio file beside this one is the audio; "
				+ "embedding it here stores the same recording twice",
				written.hasData());
		Assert.assertTrue("Frequency analysis cannot be recovered from the "
				+ "audio file and must be stored", written.hasFreqData());
		Assert.assertTrue("Feature data cannot be recovered from the audio "
				+ "file and must be stored", written.hasFeatureData());
		Assert.assertEquals(FRAMES, written.getFrameCount());
	}

	/**
	 * Omitting the audio is what makes the file small. Asserted against the
	 * same details encoded with the audio, so the comparison holds whatever
	 * else the format comes to carry.
	 */
	@Test(timeout = 30000)
	public void savedDetailsAreSmallerThanTheAudioTheyDescribe() throws IOException {
		AudioLibraryPersistence.saveWaveDetails(
				detailsWithAudio("sized-details"), tempDir.getPath());

		long written = new File(tempDir, "sized-details.bin").length();
		int withAudio = AudioLibraryPersistence
				.encode(detailsWithAudio("sized-details"), true)
				.getSerializedSize();

		Assert.assertTrue("A file of " + written + " bytes is not meaningfully "
						+ "smaller than the " + withAudio + " bytes the same "
						+ "details take with their audio embedded",
				written * 2 < withAudio);
	}

	/**
	 * Files written before the audio was dropped still contain it, and loading
	 * one must not carry that audio into memory.
	 */
	@Test(timeout = 30000)
	public void loadingAFileThatStillCarriesAudioDoesNotRetainIt() throws IOException {
		File f = new File(tempDir, "legacy-details.bin");
		try (FileOutputStream out = new FileOutputStream(f)) {
			AudioLibraryPersistence.encode(detailsWithAudio("legacy-details"), true)
					.writeTo(out);
		}

		Assert.assertTrue("This test is only meaningful against a file that "
				+ "does carry audio", read(f).hasData());

		WaveDetails loaded = AudioLibraryPersistence.loadWaveDetails(f.getPath());

		Assert.assertNull("Audio read from the file must not be held", loaded.getData());
		Assert.assertNotNull("Frequency analysis must survive the load",
				loaded.getFreqData());
		Assert.assertNotNull("Feature data must survive the load",
				loaded.getFeatureData());
		Assert.assertEquals(FRAMES, loaded.getFrameCount());
		Assert.assertEquals(SAMPLE_RATE, loaded.getSampleRate());
	}

	/**
	 * Releasing the audio destroys the memory holding it rather than leaving it
	 * for the collector.
	 *
	 * <p>A collection decoded from a message reads directly out of that
	 * message, so simply dropping the reference would keep the whole recording
	 * reachable until finalization ran. This asserts against a decoded
	 * collection specifically, since an ordinary allocation would pass either
	 * way.</p>
	 */
	@Test(timeout = 30000)
	public void releasingAudioDestroysTheMemoryHoldingIt() {
		Collections.CollectionData encoded =
				CollectionEncoder.encode(new PackedCollection(FRAMES));
		PackedCollection decoded = CollectionEncoder.decode(encoded);

		WaveDetails details = new WaveDetails("released-details", SAMPLE_RATE);
		details.setData(decoded);
		details.releaseData();

		Assert.assertNull(details.getData());
		Assert.assertTrue("Releasing the audio must destroy the memory it was "
				+ "read from, not wait for it to be collected",
				decoded.isDestroyed());
	}

	/** Releasing audio that was never set is not an error. */
	@Test(timeout = 30000)
	public void releasingAbsentAudioDoesNothing() {
		WaveDetails details = new WaveDetails("empty-details", SAMPLE_RATE);
		details.releaseData();
		Assert.assertNull(details.getData());
	}
}
