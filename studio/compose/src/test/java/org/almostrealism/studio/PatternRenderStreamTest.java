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
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
	 *
	 * <p>The uninterruptible delay is started from the interrupt that {@link PatternRenderStream#stop()}
	 * delivers to the producer, not from when the render begins, so the elapsed-time assertion does
	 * not depend on how promptly the test thread is scheduled between observing that a render is in
	 * flight and calling {@code stop()}. Since {@code stop()} interrupts the producer before joining
	 * it, waiting for that interrupt before the delay keeps the full delay inside the join window
	 * while still detecting a {@code stop()} that returns after the old two-second join timeout.</p>
	 */
	@Test(timeout = 60000)
	public void stopWaitsForRenderThatOutlastsWarningPeriod() throws InterruptedException {
		AtomicBoolean rendering = new AtomicBoolean();
		AtomicInteger completed = new AtomicInteger();
		long renderMillis = PatternRenderStream.STOP_WARNING_MILLIS + 1000;

		Runnable slowRender = () -> {
			rendering.set(true);
			// Wait for stop() to interrupt the producer, then begin the deliberately
			// uninterruptible delay so it is measured from shutdown rather than render start.
			while (!Thread.currentThread().isInterrupted()) {
				Thread.onSpinWait();
			}
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

	/**
	 * A render that calls {@link PatternRenderStream#stop()} on its own producer thread must be
	 * rejected with {@link IllegalStateException} rather than waiting for its own termination. The
	 * rejection surfaces as a prefill failure, and no buffer is recorded as rendered.
	 */
	@Test(timeout = 60000)
	public void stopFromProducerThreadIsRejected() {
		PatternRenderStream[] holder = new PatternRenderStream[1];
		AtomicReference<Throwable> fromProducer = new AtomicReference<>();

		Runnable selfStop = () -> {
			try {
				holder[0].stop();
			} catch (Throwable t) {
				fromProducer.set(t);
				throw t;
			}
		};

		PackedCollection workingInput = new PackedCollection(shape(1, BUFFER_SIZE));
		PatternRenderStream stream = new PatternRenderStream(selfStop, new long[1],
				workingInput, SLOTS, 1, BUFFER_SIZE);
		holder[0] = stream;

		try {
			HardwareException thrown = null;
			try {
				stream.start(1);
			} catch (HardwareException e) {
				thrown = e;
			}

			Assert.assertNotNull("A producer-thread stop() should surface as a prefill failure", thrown);
			Assert.assertTrue("stop() on the producer thread should be rejected with IllegalStateException, was "
					+ fromProducer.get(), fromProducer.get() instanceof IllegalStateException);
			Assert.assertEquals("No buffer should be recorded when the producer rejects its own stop()",
					0, stream.buffersRendered());
		} finally {
			stream.destroy();
			workingInput.destroy();
		}
	}

	/**
	 * A render that {@link PatternRenderStream#stop()} cancels, by failing on the interrupt
	 * {@code stop()} delivers, is not reported as a render failure: a consumer waiting for a
	 * slot is not woken with a "render-ahead failed" error, and only its own interrupt ends
	 * the wait.
	 */
	@Test(timeout = 60000)
	public void renderCancelledByStopIsNotReportedAsFailure() throws InterruptedException {
		AtomicBoolean rendering = new AtomicBoolean();
		AtomicReference<Throwable> fromConsumer = new AtomicReference<>();

		Runnable cancellableRender = () -> {
			rendering.set(true);
			while (!Thread.currentThread().isInterrupted()) {
				Thread.onSpinWait();
			}
			throw new CancellationException("render cancelled by stop()");
		};

		PackedCollection workingInput = new PackedCollection(shape(1, BUFFER_SIZE));
		PatternRenderStream stream = new PatternRenderStream(cancellableRender, new long[1],
				workingInput, SLOTS, 1, BUFFER_SIZE);

		Thread consumer = new Thread(() -> {
			try {
				stream.awaitSlot();
			} catch (Throwable t) {
				fromConsumer.set(t);
			}
		}, "pattern-render-consumer");

		try {
			stream.start(0);
			while (!rendering.get()) {
				Thread.sleep(5);
			}

			consumer.start();
			while (consumer.getState() != Thread.State.WAITING) {
				Thread.sleep(5);
			}

			stream.stop();

			consumer.join(500);
			Assert.assertTrue("A cancelled render must not wake the waiting consumer", consumer.isAlive());

			consumer.interrupt();
			consumer.join(10000);
			Assert.assertFalse("The consumer should exit on its own interrupt", consumer.isAlive());
			Assert.assertTrue("The consumer should observe its interrupt, not a render failure: "
					+ fromConsumer.get(), fromConsumer.get() instanceof HardwareException);
			Assert.assertNull("A render cancelled by stop() must not be reported as its cause",
					fromConsumer.get().getCause());
			Assert.assertEquals(0, stream.buffersRendered());
		} finally {
			consumer.interrupt();
			stream.destroy();
			workingInput.destroy();
		}
	}
}
