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

package org.almostrealism.music.pattern;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Tests for the buffer-holder contract of {@link PatternAudioBuffer}: the output
 * buffer it allocates (or adopts), the live producer it hands out, and the reset
 * that zeroes it. The actual batch render ({@link PatternAudioBuffer#prepareBatch()},
 * which delegates to {@link PatternSystemManager#sum}) is the hardware/asset-backed
 * path exercised elsewhere and is deliberately not run here; these tests pin only
 * the pure holder behaviour that needs no rendering.
 */
public class PatternAudioBufferTest extends TestSuiteBase {

	/** A context supplier the holder tests never invoke (only {@code prepareBatch} reads it). */
	private static final Supplier<AudioSceneContext> CONTEXT = AudioSceneContext::new;

	/** A frame supplier the holder tests never invoke (only {@code prepareBatch} reads it). */
	private static final IntSupplier FRAME = () -> 0;

	/**
	 * Creates a pattern system manager with no choices and no chromosomes; the buffer
	 * only retains the reference and never calls it along the paths under test.
	 *
	 * @return an empty manager
	 */
	private static PatternSystemManager patterns() {
		return new PatternSystemManager(new ArrayList<ProjectedChromosome>());
	}

	/**
	 * Creates a buffer that allocates its own output of the given size.
	 *
	 * @param bufferSize the buffer size in frames
	 * @return the buffer
	 */
	private static PatternAudioBuffer buffer(int bufferSize) {
		return new PatternAudioBuffer(patterns(), CONTEXT, new ChannelInfo(0), bufferSize, FRAME);
	}

	/** With no supplied buffer the holder allocates an output of exactly the requested size. */
	@Test(timeout = 10000)
	public void allocatesOutputOfRequestedSize() {
		ChannelInfo channel = new ChannelInfo(2, ChannelInfo.StereoChannel.RIGHT);
		PatternAudioBuffer buffer = new PatternAudioBuffer(patterns(), CONTEXT, channel, 512, FRAME);

		try {
			Assert.assertEquals(512, buffer.getBufferSize());
			Assert.assertNotNull(buffer.getOutputBuffer());
			Assert.assertEquals(512, buffer.getOutputBuffer().getMemLength());
			Assert.assertSame("the channel is retained", channel, buffer.getChannel());
		} finally {
			buffer.getOutputBuffer().destroy();
		}
	}

	/**
	 * A supplied output buffer is adopted directly rather than reallocated, and the
	 * frame-count {@code bufferSize} is retained independently of that buffer's own
	 * length — the supplied buffer is typically a delegate region into a larger
	 * consolidated buffer, so the two sizes need not agree.
	 */
	@Test(timeout = 10000)
	public void adoptsSuppliedBufferAndKeepsBufferSizeIndependent() {
		PackedCollection supplied = new PackedCollection(4);
		PatternAudioBuffer buffer = new PatternAudioBuffer(patterns(), CONTEXT,
				new ChannelInfo(0), 8, FRAME, supplied);

		try {
			Assert.assertSame("the supplied buffer is used directly", supplied, buffer.getOutputBuffer());
			Assert.assertEquals("the frame count is stored independently of the buffer length",
					8, buffer.getBufferSize());
			Assert.assertEquals(4, buffer.getOutputBuffer().getMemLength());
		} finally {
			supplied.destroy();
		}
	}

	/**
	 * The output producer reads the live output buffer: values written into the buffer
	 * after the producer is obtained are visible when it is evaluated. The producer is
	 * obtained <em>before</em> the buffer is filled so the test actually pins the
	 * live-read property — a producer that snapshotted its contents at creation time
	 * would fail these assertions.
	 */
	@Test(timeout = 10000)
	public void outputProducerReadsLiveBuffer() {
		PackedCollection supplied = new PackedCollection(4);
		PatternAudioBuffer buffer = new PatternAudioBuffer(patterns(), CONTEXT,
				new ChannelInfo(0), 4, FRAME, supplied);

		try {
			Producer<PackedCollection> producer = buffer.getOutputProducer();
			supplied.fill(0.1, 0.2, 0.3, 0.4);

			PackedCollection read = producer.get().evaluate();
			Assert.assertEquals(4, read.getMemLength());
			assertSimilar(0.1, read.toDouble(0));
			assertSimilar(0.2, read.toDouble(1));
			assertSimilar(0.3, read.toDouble(2));
			assertSimilar(0.4, read.toDouble(3));
		} finally {
			supplied.destroy();
		}
	}

	/** {@link PatternAudioBuffer#reset()} zeroes every frame of the output buffer. */
	@Test(timeout = 10000)
	public void resetZeroesTheOutputBuffer() {
		PatternAudioBuffer buffer = buffer(4);
		PackedCollection output = buffer.getOutputBuffer();
		try {
			output.fill(1.0, 2.0, 3.0, 4.0);
			Assert.assertEquals(1.0, output.toDouble(0), 0.0);

			buffer.reset();

			for (int i = 0; i < output.getMemLength(); i++) {
				Assert.assertEquals("frame " + i + " is cleared", 0.0, output.toDouble(i), 0.0);
			}
		} finally {
			output.destroy();
		}
	}

	/**
	 * {@link PatternAudioBuffer#setup()} is a no-op: it yields a runnable operation
	 * whose execution leaves the output buffer untouched.
	 */
	@Test(timeout = 10000)
	public void setupIsANoOp() {
		PatternAudioBuffer buffer = buffer(4);
		PackedCollection output = buffer.getOutputBuffer();
		try {
			output.fill(0.5, 0.6, 0.7, 0.8);

			Supplier<Runnable> setup = buffer.setup();
			Assert.assertNotNull(setup);
			setup.get().run();

			assertSimilar("setup does not disturb the buffer", 0.5, output.toDouble(0));
			assertSimilar(0.8, output.toDouble(3));
		} finally {
			output.destroy();
		}
	}

	/**
	 * {@link PatternAudioBuffer#prepareBatch()} zeroes the output before summing. With a
	 * manager that has no patterns for the channel there is nothing to sum, so a buffer
	 * primed with non-zero data is left all zeros once the batch operation runs — which
	 * pins the clear step independently of any rendered content.
	 */
	@Test(timeout = 30000)
	public void prepareBatchClearsOutputBeforeSumming() {
		PatternAudioBuffer buffer = buffer(4);
		PackedCollection output = buffer.getOutputBuffer();
		try {
			output.fill(0.5, 0.6, 0.7, 0.8);

			buffer.prepareBatch().get().run();

			for (int i = 0; i < output.getMemLength(); i++) {
				Assert.assertEquals("frame " + i + " is cleared before summing", 0.0, output.toDouble(i), 0.0);
			}
		} finally {
			output.destroy();
		}
	}

	/**
	 * {@link PatternAudioBuffer#prepareBatch(boolean)} with {@code clearOutput == false}
	 * skips the per-cell zeroing, so with no patterns to sum the output retains whatever
	 * it held before — the branch the consolidated render-ahead path relies on when it
	 * zeroes the whole shared buffer once per round instead of per cell.
	 */
	@Test(timeout = 30000)
	public void prepareBatchWithoutClearLeavesOutputIntact() {
		PatternAudioBuffer buffer = buffer(4);
		PackedCollection output = buffer.getOutputBuffer();
		try {
			output.fill(0.5, 0.6, 0.7, 0.8);

			buffer.prepareBatch(false).get().run();

			assertSimilar(0.5, output.toDouble(0));
			assertSimilar(0.6, output.toDouble(1));
			assertSimilar(0.7, output.toDouble(2));
			assertSimilar(0.8, output.toDouble(3));
		} finally {
			output.destroy();
		}
	}
}
