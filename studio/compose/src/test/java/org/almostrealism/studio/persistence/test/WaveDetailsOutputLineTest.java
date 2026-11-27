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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.persistence.WaveDetailsOutputLine;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Tests for {@link WaveDetailsOutputLine}, the output line that buffers audio
 * samples and asynchronously publishes {@link Audio.WaveDetailData} messages
 * once a recording buffer fills.
 *
 * <p>The line is driven with a small, deterministic buffer geometry
 * ({@code batchCount = 2}, {@code framesPerBatch = 4}) and a capturing
 * consumer; the asynchronous publish is awaited on a latch rather than a
 * wall-clock sleep, so the assertions are timing-independent.</p>
 *
 * @see WaveDetailsOutputLine
 */
public class WaveDetailsOutputLineTest extends TestSuiteBase {

	/** Sample rate the line under test is configured with. */
	private static final int SAMPLE_RATE = 44100;

	/** Number of batches per recording buffer. */
	private static final int BATCH_COUNT = 2;

	/** Number of frames per batch. */
	private static final int FRAMES_PER_BATCH = 4;

	/** Writes {@code count} full-size batches of samples into the line. */
	private static void writeBatches(WaveDetailsOutputLine line, int count) {
		for (int i = 0; i < count; i++) {
			PackedCollection sample = new PackedCollection(FRAMES_PER_BATCH);
			sample.fill(0.1, 0.2, 0.3, 0.4);
			line.write(sample);
		}
	}

	/** The configured sample rate and the derived buffer size are reported back. */
	@Test(timeout = 90000)
	public void reportsConfiguredSampleRateAndBufferSize() {
		WaveDetailsOutputLine line = new WaveDetailsOutputLine(
				SAMPLE_RATE, BATCH_COUNT, FRAMES_PER_BATCH, data -> { });
		try {
			Assert.assertEquals("Sample rate must be reported", SAMPLE_RATE, line.getSampleRate());
			Assert.assertEquals("Buffer size is batchCount * framesPerBatch",
					BATCH_COUNT * FRAMES_PER_BATCH, line.getBufferSize());
		} finally {
			line.destroy();
		}
	}

	/** The line is inactive until activated, and the flag reflects the change. */
	@Test(timeout = 90000)
	public void activeFlagDefaultsFalseAndToggles() {
		WaveDetailsOutputLine line = new WaveDetailsOutputLine(
				SAMPLE_RATE, BATCH_COUNT, FRAMES_PER_BATCH, data -> { });
		try {
			Assert.assertFalse("A fresh line must be inactive", line.isActive());
			line.setActive(true);
			Assert.assertTrue("setActive(true) must activate the line", line.isActive());
			line.setActive(false);
			Assert.assertFalse("setActive(false) must deactivate the line", line.isActive());
		} finally {
			line.destroy();
		}
	}

	/** The silence detector is null until set, then returned as supplied. */
	@Test(timeout = 90000)
	public void silenceDetectorAccessorRoundTrips() {
		WaveDetailsOutputLine line = new WaveDetailsOutputLine(
				SAMPLE_RATE, BATCH_COUNT, FRAMES_PER_BATCH, data -> { });
		try {
			Assert.assertNull("No silence detector is configured by default", line.getSilenceDetector());
			BooleanSupplier detector = () -> true;
			line.setSilenceDetector(detector);
			Assert.assertSame("The configured detector must be returned",
					detector, line.getSilenceDetector());
		} finally {
			line.destroy();
		}
	}

	/** A sample whose length is not a full batch is rejected. */
	@Test(timeout = 90000)
	public void writeRejectsSampleOfWrongSize() {
		WaveDetailsOutputLine line = new WaveDetailsOutputLine(
				SAMPLE_RATE, BATCH_COUNT, FRAMES_PER_BATCH, data -> { });
		line.setActive(true);
		try {
			line.write(new PackedCollection(FRAMES_PER_BATCH - 1));
			Assert.fail("A sample that is not exactly framesPerBatch must be rejected");
		} catch (IllegalArgumentException expected) {
			/* The contract: the write buffer only accepts full, batch-sized writes. */
		} finally {
			line.destroy();
		}
	}

	/** An inactive line fills its buffer but publishes nothing. */
	@Test(timeout = 90000)
	public void inactiveLinePublishesNothing() {
		List<Audio.WaveDetailData> received = Collections.synchronizedList(new ArrayList<>());
		WaveDetailsOutputLine line = new WaveDetailsOutputLine(
				SAMPLE_RATE, BATCH_COUNT, FRAMES_PER_BATCH, received::add);
		try {
			writeBatches(line, BATCH_COUNT);
			Assert.assertTrue("An inactive line must publish no messages", received.isEmpty());
		} finally {
			line.destroy();
		}
	}

	/**
	 * An active line publishes exactly one message per batch once a buffer
	 * fills, each message carrying the configured sample rate, the batch frame
	 * count, and the silence flag reported during the write.
	 *
	 * @throws InterruptedException if the wait for the async publish is interrupted
	 */
	@Test(timeout = 120000)
	public void activeLinePublishesOneSilentMessagePerBatch() throws InterruptedException {
		List<Audio.WaveDetailData> received = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch latch = new CountDownLatch(BATCH_COUNT);
		Consumer<Audio.WaveDetailData> consumer = data -> {
			received.add(data);
			latch.countDown();
		};

		WaveDetailsOutputLine line = new WaveDetailsOutputLine(
				SAMPLE_RATE, BATCH_COUNT, FRAMES_PER_BATCH, consumer);
		try {
			line.setActive(true);
			line.setSilenceDetector(() -> true);

			writeBatches(line, BATCH_COUNT);

			Assert.assertTrue("The async publish must complete within the timeout",
					latch.await(30, TimeUnit.SECONDS));
			Assert.assertEquals("One message must be published per batch",
					BATCH_COUNT, received.size());
			for (Audio.WaveDetailData data : received) {
				Assert.assertEquals("Each message carries the line's sample rate",
						SAMPLE_RATE, data.getSampleRate());
				Assert.assertEquals("Each message carries the batch frame count",
						FRAMES_PER_BATCH, data.getFrameCount());
				Assert.assertTrue("Every batch was flagged silent during the write",
						data.getSilent());
			}
		} finally {
			line.destroy();
		}
	}
}
