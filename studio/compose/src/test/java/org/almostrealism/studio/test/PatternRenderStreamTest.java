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

package org.almostrealism.studio.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.PatternRenderStream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests the lifecycle of {@link PatternRenderStream}: {@code start}, {@code stop} and
 * {@code destroy} replace the shared producer thread, so they must not interleave.
 */
public class PatternRenderStreamTest extends TestSuiteBase {

	/** Rows in the working input. */
	private static final int CHANNELS = 2;

	/** Frames per buffer. */
	private static final int BUFFER_SIZE = 64;

	/** Ring depth. */
	private static final int SLOTS = 2;

	/**
	 * A {@code stop()} issued from another thread while {@code start()} is still prefilling
	 * must wait for {@code start()} to finish rather than run alongside it. The render
	 * operation blocks until the test lets it proceed, holding {@code start()} inside its
	 * prefill; a concurrent {@code stop()} is then issued. {@code start()} must still return
	 * only after its prefill buffer is rendered, without error, and the stop must then
	 * terminate the producer. Before the fix the two ran concurrently: {@code stop()} cleared
	 * the running flag and the producer field underneath {@code start()}, which then returned
	 * early with nothing rendered (or dereferenced the cleared field).
	 */
	@Test(timeout = 60_000)
	public void stopDuringStartWaitsForPrefill() throws InterruptedException {
		CountDownLatch rendering = new CountDownLatch(1);
		Semaphore proceed = new Semaphore(0);
		AtomicLong renders = new AtomicLong();
		Runnable renderOp = () -> {
			rendering.countDown();
			proceed.acquireUninterruptibly();
			proceed.release();
			renders.incrementAndGet();
		};

		PackedCollection input = new PackedCollection(CHANNELS, BUFFER_SIZE);
		PatternRenderStream stream = new PatternRenderStream(renderOp, new long[1],
				input, SLOTS, CHANNELS, BUFFER_SIZE);

		try {
			AtomicLong renderedAtStart = new AtomicLong(-1);
			AtomicReference<Throwable> startError = new AtomicReference<>();
			Thread starter = new Thread(() -> {
				try {
					stream.start(1);
					renderedAtStart.set(renders.get());
				} catch (Throwable t) {
					startError.set(t);
				}
			}, "stream-start");
			starter.start();
			assertTrue(rendering.await(30, TimeUnit.SECONDS));

			CountDownLatch stopped = new CountDownLatch(1);
			Thread stopper = new Thread(() -> {
				stream.stop();
				stopped.countDown();
			}, "stream-stop");
			stopper.start();

			assertFalse("stop() must not complete while start() is prefilling",
					stopped.await(500, TimeUnit.MILLISECONDS));

			proceed.release();
			starter.join(30_000);
			assertTrue("stop() must complete once start() returns", stopped.await(30, TimeUnit.SECONDS));
			stopper.join(30_000);

			assertTrue("start() must not fail when a stop() races it: " + startError.get(),
					startError.get() == null);
			assertTrue("start() must return only after its prefill buffer is rendered",
					renderedAtStart.get() >= 1);
			assertEquals("stop() must reset the render cursor", 0, stream.buffersRendered());
		} finally {
			proceed.release();
			stream.destroy();
			input.destroy();
		}
	}

	/**
	 * Repeated {@code stop()} and {@code destroy()} after a completed run must be harmless:
	 * the producer is stopped once, and each later call is a no-op that leaves the cursors at
	 * zero.
	 */
	@Test(timeout = 60_000)
	public void repeatedStopAfterStartIsHarmless() {
		PackedCollection input = new PackedCollection(CHANNELS, BUFFER_SIZE);
		PatternRenderStream stream = new PatternRenderStream(() -> { }, new long[1],
				input, SLOTS, CHANNELS, BUFFER_SIZE);

		try {
			stream.start(SLOTS);
			assertTrue(stream.buffersRendered() >= SLOTS);

			stream.stop();
			stream.stop();
			assertEquals(0, stream.buffersRendered());
			assertEquals(0, stream.buffersConsumed());
		} finally {
			stream.destroy();
			stream.destroy();
			input.destroy();
		}
	}
}
