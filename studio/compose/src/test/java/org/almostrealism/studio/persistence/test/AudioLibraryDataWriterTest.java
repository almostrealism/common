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

import org.almostrealism.audio.api.Audio;
import org.almostrealism.studio.persistence.AudioLibraryDataWriter;
import org.almostrealism.studio.persistence.LibraryDestination;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Tests for {@link AudioLibraryDataWriter}, the buffering writer that groups
 * {@link Audio.WaveDetailData} entries into {@link Audio.WaveRecording}s keyed
 * by sample and group and asynchronously persists them to a {@link
 * LibraryDestination}.
 *
 * <p>The silence-driven sample state machine and the group-limit transitions
 * are driven entirely through the public API and asserted on observable
 * effects — the sample-end callback, the thrown guards, and the recordings
 * that reach the destination.</p>
 *
 * @see AudioLibraryDataWriter
 */
public class AudioLibraryDataWriterTest extends TestSuiteBase {

	/** Frame count assigned to every synthetic wave-detail entry. */
	private static final int FRAME_COUNT = 100;

	/** Root temporary directory for the destination, removed in teardown. */
	private Path tempDir;

	/** The destination backing the writer under test. */
	private LibraryDestination destination;

	/**
	 * Creates the temporary directory and an append-mode destination.
	 *
	 * @throws IOException if directory creation fails
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("audio-library-data-writer-test");
		destination = new LibraryDestination(tempDir.resolve("lib").toString(), true);
	}

	/**
	 * Recursively deletes the test's temporary directory.
	 *
	 * @throws IOException if directory traversal fails
	 */
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

	/** Builds a wave-detail entry with the fixed frame count and the given silence flag. */
	private static Audio.WaveDetailData detail(boolean silent) {
		return Audio.WaveDetailData.newBuilder()
				.setSampleRate(44100)
				.setChannelCount(1)
				.setFrameCount(FRAME_COUNT)
				.setSilent(silent)
				.build();
	}

	/** Polls for the first batch file at the destination prefix, up to a bounded wait. */
	private boolean awaitFirstBatch() throws InterruptedException {
		File batch = new File(tempDir.resolve("lib").toString() + "_0.bin");
		for (int i = 0; i < 200 && !batch.exists(); i++) {
			Thread.sleep(50);
		}
		return batch.exists();
	}

	/** A group is exclusive: starting a second while one is active is rejected. */
	@Test(timeout = 90000)
	public void startRejectsSecondStartWhileGroupActive() {
		AudioLibraryDataWriter writer = new AudioLibraryDataWriter(destination);
		Assert.assertEquals("start(key) returns the key", "g1", writer.start("g1"));
		try {
			writer.start("g2");
			Assert.fail("Starting a second group while one is active must be rejected");
		} catch (IllegalArgumentException expected) {
			/* The contract: a group must be reset before another can start. */
		}
	}

	/** Configuration accessors round-trip and the frame limit defaults to unlimited. */
	@Test(timeout = 90000)
	public void configurationAccessorsRoundTrip() {
		AudioLibraryDataWriter writer = new AudioLibraryDataWriter(destination);

		Assert.assertNull("No sample listener by default", writer.getSampleListener());
		Assert.assertNull("No group key provider by default", writer.getGroupKeyProvider());
		Assert.assertEquals("No frame limit by default", 0, writer.getGroupFrameLimit());
		Assert.assertFalse("An unlimited group is never over its limit", writer.isGroupLimitExceeded());

		writer.setGroupFrameLimit(500);
		Assert.assertEquals("The frame limit must be stored", 500, writer.getGroupFrameLimit());
		Assert.assertFalse("A fresh group has queued no frames yet", writer.isGroupLimitExceeded());
	}

	/** A second concurrent sample within a group is rejected. */
	@Test(timeout = 90000)
	public void startSampleRejectsWhenSampleAlreadyActive() {
		AudioLibraryDataWriter writer = new AudioLibraryDataWriter(destination);
		writer.setSampleListener(key -> { });
		writer.start("g1");

		writer.bufferData(detail(false));
		try {
			writer.startSample();
			Assert.fail("A second concurrent sample must be rejected");
		} catch (UnsupportedOperationException expected) {
			/* The contract: only one sample is in progress at a time. */
		}
	}

	/**
	 * Sound starts a sample and the following silence ends it, notifying the
	 * sample listener once with the sample's generated key.
	 */
	@Test(timeout = 90000)
	public void soundThenSilenceEndsSampleAndNotifiesListener() {
		List<String> endedSamples = new ArrayList<>();
		AudioLibraryDataWriter writer = new AudioLibraryDataWriter(destination);
		writer.setSampleListener(endedSamples::add);
		writer.start("g1");

		writer.bufferData(detail(false));
		writer.bufferData(detail(false));
		writer.bufferData(detail(true));

		Assert.assertEquals("Exactly one sample should have ended", 1, endedSamples.size());
		Assert.assertNotNull("The ended sample must carry a generated key", endedSamples.get(0));
		Assert.assertFalse("The sample key must not be blank", endedSamples.get(0).isBlank());
	}

	/** {@link AudioLibraryDataWriter#restart(String)} swaps the active group key. */
	@Test(timeout = 90000)
	public void restartSwapsActiveGroupKey() {
		AudioLibraryDataWriter writer = new AudioLibraryDataWriter(destination);
		writer.setSampleListener(key -> { });
		writer.start("g1");

		Assert.assertEquals("restart returns the new key", "g2", writer.restart("g2"));
		try {
			writer.start("g3");
			Assert.fail("A group remains active after restart, so start must be rejected");
		} catch (IllegalArgumentException expected) {
			/* The contract: restart leaves a fresh group active. */
		}
	}

	/**
	 * Buffered recordings are flushed to the destination on {@link
	 * AudioLibraryDataWriter#reset()} and read back carrying the group key.
	 *
	 * @throws Exception if the destination write or wait fails
	 */
	@Test(timeout = 120000)
	public void bufferedRecordingsReachTheDestinationOnReset() throws Exception {
		AudioLibraryDataWriter writer = new AudioLibraryDataWriter(destination);
		writer.setSampleListener(key -> { });
		writer.start("group-key");

		writer.bufferData(detail(false));
		writer.bufferData(detail(false));
		writer.bufferData(detail(true));
		writer.reset();

		Assert.assertTrue("A batch file must be written by the flush", awaitFirstBatch());

		List<Audio.AudioLibraryData> batches = destination.load();
		List<Audio.WaveRecording> recordings = new ArrayList<>();
		batches.forEach(b -> recordings.addAll(b.getRecordingsList()));

		Assert.assertFalse("At least one recording must have been written", recordings.isEmpty());
		Assert.assertTrue("Every recording must carry the active group key",
				recordings.stream().allMatch(r -> "group-key".equals(r.getGroupKey())));
	}

	/**
	 * When the group frame limit is exceeded, the writer obtains a new group key
	 * from the configured provider rather than failing.
	 */
	@Test(timeout = 120000)
	public void exceedingGroupFrameLimitRequestsANewGroupKey() {
		AtomicInteger providerCalls = new AtomicInteger();
		AudioLibraryDataWriter writer = new AudioLibraryDataWriter(destination);
		writer.setSampleListener(key -> { });
		// A limit a single recording crosses but does not overshoot by the grace
		// factor (1.3x), so the limit is handled by a provider-supplied restart.
		writer.setGroupFrameLimit(FRAME_COUNT - 10);
		writer.setGroupKeyProvider(() -> "group-" + providerCalls.incrementAndGet());
		writer.start("group-0");

		// One sound/silence cycle queues a recording whose frames exceed the limit,
		// which must trigger a provider-supplied group restart.
		writer.bufferData(detail(false));
		writer.bufferData(detail(true));

		Assert.assertTrue("The group key provider must be consulted when the limit is exceeded",
				providerCalls.get() >= 1);
		Assert.assertFalse("The frame tally resets with the new group",
				writer.isGroupLimitExceeded());
	}
}
