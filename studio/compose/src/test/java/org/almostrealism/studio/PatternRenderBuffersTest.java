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

package org.almostrealism.studio;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Verifies the consolidated-buffer lifecycle of {@link PatternRenderBuffers}.
 *
 * <p>A single {@link PatternRenderBuffers} instance is reused across every runner
 * build on one {@code AudioScene}, so {@link PatternRenderBuffers#consolidate(int, int)}
 * must release the buffer retained from a previous build before allocating the
 * replacement — otherwise each rebuild would strand the prior consolidated
 * allocation (which {@code AudioScene.destroy()} can no longer reach once the field
 * has been overwritten) until reference-queue cleanup.</p>
 */
public class PatternRenderBuffersTest extends TestSuiteBase {

	/** Channels for the consolidated layout under test. */
	private static final int CHANNELS = 2;

	/** Frames per render region. */
	private static final int BUFFER_SIZE = 128;

	/**
	 * Confirms that re-consolidating destroys the previously allocated buffer and
	 * installs a correctly sized replacement, so repeated runner builds do not leak.
	 */
	@Test(timeout = 60000)
	public void reconsolidateReleasesPreviousBuffer() {
		PatternRenderBuffers buffers = new PatternRenderBuffers();

		buffers.consolidate(CHANNELS, BUFFER_SIZE);
		PackedCollection first = buffers.getBuffer();

		Assert.assertNotNull("First consolidation must allocate a buffer", first);
		Assert.assertNotNull("Freshly allocated buffer must hold memory", first.getMem());
		Assert.assertEquals("Buffer size must be bufferSize x channels x 4",
				BUFFER_SIZE * CHANNELS * 4, first.getMemLength());

		buffers.consolidate(CHANNELS, BUFFER_SIZE);
		PackedCollection second = buffers.getBuffer();

		Assert.assertNotSame("Re-consolidation must install a new buffer", first, second);
		Assert.assertNull("The previous buffer must be destroyed on re-consolidation",
				first.getMem());
		Assert.assertNotNull("The replacement buffer must hold memory", second.getMem());

		buffers.destroy();
		Assert.assertNull("destroy() must release the live buffer", second.getMem());
	}

	/**
	 * Confirms the first consolidation is safe when no buffer was previously allocated,
	 * so the added release guard does not fault on the initial build.
	 */
	@Test(timeout = 60000)
	public void firstConsolidateWithoutPriorBufferSucceeds() {
		PatternRenderBuffers buffers = new PatternRenderBuffers();

		Assert.assertNull("No buffer exists before the first consolidation",
				buffers.getBuffer());

		buffers.consolidate(CHANNELS, BUFFER_SIZE);

		Assert.assertNotNull("First consolidation must allocate a buffer",
				buffers.getBuffer());
		Assert.assertNotNull("Allocated buffer must hold memory",
				buffers.getBuffer().getMem());

		buffers.destroy();
	}

	/**
	 * Starts a render-ahead stream whose render operation records the producer thread,
	 * then verifies that {@link PatternRenderBuffers#stopStreams()} leaves that thread
	 * terminated, and that a subsequent {@link PatternRenderBuffers#destroy()} is safe.
	 */
	@Test(timeout = 60000)
	public void stopStreamsHaltsProducerThread() {
		AtomicReference<Thread> producer = new AtomicReference<>();
		PackedCollection workingInput = new PackedCollection(shape(1, BUFFER_SIZE));
		PatternRenderStream stream = new PatternRenderStream(
				() -> producer.set(Thread.currentThread()),
				new long[1], workingInput, 2, 1, BUFFER_SIZE);

		PatternRenderBuffers buffers = new PatternRenderBuffers();
		buffers.addStream(stream);
		stream.start(1);

		try {
			Thread thread = producer.get();
			Assert.assertNotNull("Producer should have rendered during prefill", thread);
			Assert.assertTrue("Producer should be running after start", thread.isAlive());

			buffers.stopStreams();
			Assert.assertFalse("Producer should be stopped by stopStreams", thread.isAlive());

			buffers.destroy();
		} finally {
			stream.destroy();
			workingInput.destroy();
		}
	}

	/**
	 * A stream stopped by {@link PatternRenderBuffers#stopStreams()} (as a settings reload
	 * does) and then restarted by its surviving runner stays tracked, so a later
	 * {@link PatternRenderBuffers#destroy()} still stops its producer.
	 */
	@Test(timeout = 60000)
	public void destroyStopsStreamRestartedAfterStopStreams() {
		AtomicReference<Thread> producer = new AtomicReference<>();
		PackedCollection workingInput = new PackedCollection(shape(1, BUFFER_SIZE));
		PatternRenderStream stream = new PatternRenderStream(
				() -> producer.set(Thread.currentThread()),
				new long[1], workingInput, 2, 1, BUFFER_SIZE);

		PatternRenderBuffers buffers = new PatternRenderBuffers();
		buffers.addStream(stream);

		try {
			stream.start(1);
			buffers.stopStreams();

			producer.set(null);
			stream.start(1);
			Thread restarted = producer.get();
			Assert.assertNotNull("Restarted producer should have rendered during prefill", restarted);
			Assert.assertTrue("Restarted producer should be running", restarted.isAlive());

			buffers.destroy();
			Assert.assertFalse("destroy() should stop a producer restarted after stopStreams()",
					restarted.isAlive());
		} finally {
			stream.destroy();
			workingInput.destroy();
		}
	}

	/**
	 * A stream registered after {@link PatternRenderBuffers#destroy()} has released the
	 * buffer — a runner finishing its build while the scene is concurrently torn down — must
	 * be rejected and stopped, not tracked against freed storage. The stopped producer can no
	 * longer render into the released buffer when its runner later starts it.
	 */
	@Test(timeout = 60000)
	public void addStreamAfterDestroyStopsLateRegistration() {
		PatternRenderBuffers buffers = new PatternRenderBuffers();
		buffers.consolidate(CHANNELS, BUFFER_SIZE);
		buffers.destroy();

		AtomicReference<Thread> producer = new AtomicReference<>();
		PackedCollection workingInput = new PackedCollection(shape(1, BUFFER_SIZE));
		PatternRenderStream late = new PatternRenderStream(
				() -> producer.set(Thread.currentThread()),
				new long[1], workingInput, 2, 1, BUFFER_SIZE);

		try {
			late.start(1);
			Thread thread = producer.get();
			Assert.assertNotNull("Late stream should have rendered during prefill", thread);
			Assert.assertTrue("Late stream producer should be running before registration",
					thread.isAlive());

			buffers.addStream(late);

			Assert.assertFalse("A stream registered after destroy() must be stopped, not tracked",
					thread.isAlive());
			Assert.assertEquals("A rejected stream's counters are reset by the stop",
					0, late.buffersRendered());
		} finally {
			late.destroy();
			workingInput.destroy();
		}
	}

	/**
	 * {@link PatternRenderBuffers#destroy()} releases the consolidated buffer only after a
	 * render in progress on the producer thread has finished with it.
	 *
	 * @throws InterruptedException if the test thread is interrupted while waiting
	 */
	@Test(timeout = 60000)
	public void destroyWaitsForRenderBeforeReleasingBuffer() throws InterruptedException {
		assertReleaseWaitsForRender(PatternRenderBuffers::destroy);
	}

	/**
	 * Re-consolidating for a new runner build releases the previous buffer only after the
	 * render-ahead producer of the earlier build, which renders into that buffer, has
	 * stopped.
	 *
	 * @throws InterruptedException if the test thread is interrupted while waiting
	 */
	@Test(timeout = 60000)
	public void reconsolidateStopsPreviousBuildBeforeReleasingBuffer() throws InterruptedException {
		assertReleaseWaitsForRender(buffers -> buffers.consolidate(CHANNELS, BUFFER_SIZE));
	}

	/**
	 * Runs {@code release} while a tracked producer is in the middle of a render that reads
	 * the consolidated buffer, and asserts that the render never observed the buffer
	 * released and that the buffer was released by the time {@code release} returned.
	 *
	 * <p>The render spins, reading the buffer, until the producer is interrupted (which only
	 * {@link PatternRenderStream#stop()} does), and keeps reading it for a further interval
	 * afterwards, as a render that is mid-flight when stopped would. A release that does not
	 * stop the producer first frees the buffer while the render is spinning, which the render
	 * observes; a release that stops it first cannot.</p>
	 *
	 * @param release the operation that releases the consolidated buffer
	 * @throws InterruptedException if the test thread is interrupted while waiting
	 */
	private void assertReleaseWaitsForRender(Consumer<PatternRenderBuffers> release)
			throws InterruptedException {
		PatternRenderBuffers buffers = new PatternRenderBuffers();
		buffers.consolidate(CHANNELS, BUFFER_SIZE);
		PackedCollection target = buffers.getBuffer();

		AtomicBoolean rendering = new AtomicBoolean();
		AtomicBoolean releasedUnderRender = new AtomicBoolean();
		Runnable render = () -> {
			rendering.set(true);
			long deadline = System.nanoTime() + 10_000_000_000L;
			while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
				if (target.getMem() == null) releasedUnderRender.set(true);
				Thread.onSpinWait();
			}

			long tail = System.nanoTime() + 200_000_000L;
			while (System.nanoTime() < tail) {
				if (target.getMem() == null) releasedUnderRender.set(true);
				Thread.onSpinWait();
			}
		};

		PackedCollection workingInput = new PackedCollection(shape(1, BUFFER_SIZE));
		PatternRenderStream stream = new PatternRenderStream(render, new long[1],
				workingInput, 2, 1, BUFFER_SIZE);
		buffers.addStream(stream);

		try {
			stream.start(0);
			while (!rendering.get()) {
				Thread.sleep(5);
			}

			release.accept(buffers);

			// Let the render run to completion (a no-op if release already stopped it), so
			// it has read the buffer after release returned before the outcome is checked.
			stream.stop();

			Assert.assertFalse("The buffer was released while a render was still using it",
					releasedUnderRender.get());
			Assert.assertNull("The buffer must be released once the render has stopped",
					target.getMem());
		} finally {
			stream.destroy();
			buffers.destroy();
			workingInput.destroy();
		}
	}
}
