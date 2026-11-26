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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.CancellationException;

/**
 * Verifies that the default batched rendering path
 * ({@link BatchedPatternLayerRenderer#render}) polls the thread-bound
 * {@link PatternRenderCancellation} signal before it begins a tick's work, so a
 * stopped render-ahead producer stops promptly on the all-batch path rather than
 * running the whole tick before shutdown can observe it.
 *
 * <p>The batched path is the production default
 * ({@link PatternLayerManager#enableBatched} is on), and it does not reach the
 * per-note {@link PatternFeatures#renderNotes} poll for an all-batch window, so this
 * pre-gather poll is the cancellation point that bounds shutdown there. These tests
 * exercise it directly — without a compiled kernel dispatch — so a regression that
 * removes the poll is caught by the suite rather than only by a stalled {@code stop()}.</p>
 */
public class BatchedPatternLayerRendererCancellationTest extends TestSuiteBase {

	/** Audio sample rate for a renderer; no kernel is compiled in these tests. */
	private static final int SAMPLE_RATE = 44100;

	/** FIR filter order for a renderer; no kernel is compiled in these tests. */
	private static final int FILTER_ORDER = 32;

	/** A distinctive start frame so the thrown message identifies the pre-gather poll. */
	private static final int START_FRAME = 4096;

	/**
	 * Builds a scene context whose destination is non-null, the only field
	 * {@link BatchedPatternLayerRenderer#render} reads before its cancellation poll.
	 */
	private AudioSceneContext contextWithDestination(int frameCount) {
		AudioSceneContext context = new AudioSceneContext();
		context.setDestination(new PackedCollection(frameCount));
		return context;
	}

	/**
	 * A cancelled, thread-bound signal makes {@code render()} throw
	 * {@link CancellationException} at its pre-gather poll — before any note gather —
	 * naming the start frame it was polling at.
	 */
	@Test(timeout = 30_000)
	public void renderStopsAtPreGatherPollWhenCancelled() {
		BatchedPatternLayerRenderer renderer = new BatchedPatternLayerRenderer(SAMPLE_RATE, FILTER_ORDER);
		AudioSceneContext context = contextWithDestination(START_FRAME + 1024);

		PatternRenderCancellation cancellation = new PatternRenderCancellation();
		cancellation.bind();
		cancellation.cancel();

		try {
			// Were the pre-gather poll removed, this empty-element render would complete
			// normally; the thrown cancellation proves the poll ran at render() entry.
			renderer.render(context, null, new ArrayList<>(), false, 0.0, START_FRAME, 1024, null);
			Assert.fail("render() should have been cancelled at the pre-gather poll");
		} catch (CancellationException e) {
			Assert.assertTrue("message should name the start frame polled: " + e.getMessage(),
					e.getMessage().contains("frame " + START_FRAME));
			Assert.assertTrue("the cancellation request must remain set", cancellation.isCancelled());
		} finally {
			cancellation.unbind();
			renderer.destroy();
			context.getDestination().destroy();
		}
	}

	/**
	 * A bound-but-uncancelled signal lets {@code render()} pass its pre-gather poll and
	 * complete normally for an empty element set (nothing to batch), confirming the poll
	 * does not spuriously abort an uncancelled render.
	 */
	@Test(timeout = 30_000)
	public void renderPassesPreGatherPollWhenNotCancelled() {
		BatchedPatternLayerRenderer renderer = new BatchedPatternLayerRenderer(SAMPLE_RATE, FILTER_ORDER);
		AudioSceneContext context = contextWithDestination(START_FRAME + 1024);

		PatternRenderCancellation cancellation = new PatternRenderCancellation();
		cancellation.bind();
		try {
			// No elements means no gather result, no dispatch, and no compiled kernel: the
			// render returns once it has passed the (uncancelled) pre-gather poll.
			renderer.render(context, null, new ArrayList<>(), false, 0.0, START_FRAME, 1024, null);
			Assert.assertFalse("an uncancelled render must not mark its signal cancelled",
					cancellation.isCancelled());
		} finally {
			cancellation.unbind();
			renderer.destroy();
			context.getDestination().destroy();
		}
	}

	/**
	 * With no signal bound to the thread at all, {@code render()} is never cancelled and
	 * completes normally — a render outside a render-ahead producer carries no signal.
	 */
	@Test(timeout = 30_000)
	public void renderIsNeverCancelledWithoutABoundSignal() {
		BatchedPatternLayerRenderer renderer = new BatchedPatternLayerRenderer(SAMPLE_RATE, FILTER_ORDER);
		AudioSceneContext context = contextWithDestination(START_FRAME + 1024);

		Assert.assertFalse("precondition: no signal bound to this thread",
				PatternRenderCancellation.currentlyCancelled());
		try {
			renderer.render(context, null, new ArrayList<>(), false, 0.0, START_FRAME, 1024, null);
		} finally {
			renderer.destroy();
			context.getDestination().destroy();
		}
	}
}
