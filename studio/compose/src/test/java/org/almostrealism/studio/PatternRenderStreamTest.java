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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies the shutdown contract of {@link PatternRenderStream}: {@link PatternRenderStream#stop()}
 * must not return while the producer thread is still rendering, because its callers release the
 * ring and the render cells immediately afterwards.
 */
public class PatternRenderStreamTest extends TestSuiteBase {

	/** Ring depth of the stream under test. */
	private static final int SLOTS = 2;

	/** Frames per buffer. */
	private static final int BUFFER_SIZE = 64;

	/**
	 * A render that is still running when the stream is stopped, and that outlasts the
	 * warning period, must have finished by the time {@code stop()} returns.
	 */
	@Test(timeout = 60000)
	public void stopWaitsForRenderThatOutlastsWarningPeriod() throws InterruptedException {
		AtomicBoolean rendering = new AtomicBoolean();
		AtomicInteger completed = new AtomicInteger();
		long renderMillis = PatternRenderStream.STOP_WARNING_MILLIS + 1000;

		Runnable slowRender = () -> {
			rendering.set(true);
			long deadline = System.nanoTime() + renderMillis * 1_000_000L;
			while (System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			completed.incrementAndGet();
			rendering.set(false);
		};

		PackedCollection workingInput = new PackedCollection(shape(1, BUFFER_SIZE));
		PatternRenderStream stream = new PatternRenderStream(slowRender, new long[1],
				workingInput, SLOTS, 1, BUFFER_SIZE);

		try {
			stream.start(0);
			while (!rendering.get()) {
				Thread.sleep(5);
			}

			long started = System.currentTimeMillis();
			stream.stop();
			long elapsed = System.currentTimeMillis() - started;

			Assert.assertFalse("stop() returned while the producer was still rendering", rendering.get());
			Assert.assertEquals("The in-flight render should have run to completion", 1, completed.get());
			Assert.assertTrue("stop() should have waited past the warning period, waited " + elapsed + "ms",
					elapsed >= PatternRenderStream.STOP_WARNING_MILLIS);
			Assert.assertEquals(0, stream.buffersRendered());
			Assert.assertEquals(0, stream.buffersConsumed());
		} finally {
			stream.destroy();
			workingInput.destroy();
		}
	}

	/**
	 * A producer idling on a full ring is woken by the interrupt and exits promptly, and the
	 * stream rewinds its counters so it can be restarted.
	 */
	@Test(timeout = 60000)
	public void stopReturnsPromptlyForIdleProducerAndAllowsRestart() {
		AtomicInteger renders = new AtomicInteger();
		PackedCollection workingInput = new PackedCollection(shape(1, BUFFER_SIZE));
		PatternRenderStream stream = new PatternRenderStream(renders::incrementAndGet, new long[1],
				workingInput, SLOTS, 1, BUFFER_SIZE);

		try {
			stream.start(SLOTS);
			Assert.assertEquals(SLOTS, stream.buffersRendered());

			long started = System.currentTimeMillis();
			stream.stop();
			long elapsed = System.currentTimeMillis() - started;

			Assert.assertTrue("Stopping an idle producer should not wait for the warning period, waited "
					+ elapsed + "ms", elapsed < PatternRenderStream.STOP_WARNING_MILLIS);
			Assert.assertEquals(SLOTS, renders.get());
			Assert.assertEquals(0, stream.buffersRendered());
			Assert.assertFalse("stop() must not leave the caller interrupted", Thread.currentThread().isInterrupted());

			stream.start(1);
			Assert.assertTrue("A restarted stream should render again", stream.buffersRendered() >= 1);
		} finally {
			stream.destroy();
			workingInput.destroy();
		}
	}
}
