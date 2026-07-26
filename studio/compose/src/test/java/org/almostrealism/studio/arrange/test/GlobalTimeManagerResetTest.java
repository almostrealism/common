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

package org.almostrealism.studio.arrange.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.studio.arrange.GlobalTimeManager;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Receipts for arrangement-break clock resets: a {@link GlobalTimeManager} with a
 * registered reset must snap its looping frame counter back to zero when the total
 * frame count reaches the scheduled frame — both when the tick operation runs
 * standalone (the legacy CellList shape) and when it is compiled inside a
 * {@code loop(...)} that advances one buffer per run (the shape
 * {@code AudioSceneRealtimeRunner.createPdsl} uses to drive the clock).
 *
 * <p>These receipts exist because the audible symptom of a missed reset is
 * indirect and slow — automation simply keeps building instead of snapping back
 * at the break — so the clock behavior must be pinned at the frame level.</p>
 */
public class GlobalTimeManagerResetTest extends TestSuiteBase implements CellFeatures {

	/** Frames per measure used by the test's measure-to-frame mapping. */
	private static final int FRAMES_PER_MEASURE = 100;

	/**
	 * Builds a time manager whose measures span {@link #FRAMES_PER_MEASURE} frames,
	 * with a reset registered at the given measure, and runs its setup.
	 *
	 * @param resetMeasure the measure at which the clock should reset
	 * @return the prepared time manager
	 */
	private GlobalTimeManager prepare(int resetMeasure) {
		GlobalTimeManager time = new GlobalTimeManager(m -> m * FRAMES_PER_MEASURE);
		time.addReset(resetMeasure);
		time.setup().get().run();
		return time;
	}

	/**
	 * The looping counter must return to zero when the total frame count reaches
	 * the scheduled reset frame, then continue counting from zero.
	 */
	@Test(timeout = 120000)
	public void resetFiresAtScheduledFrame() {
		GlobalTimeManager time = prepare(4);
		Runnable tick = time.tick().get();

		for (int i = 0; i < 399; i++) tick.run();
		Assert.assertEquals("clock must reach the frame before the reset",
				399.0, time.getClock().getFrame(), 0.0);

		tick.run();
		Assert.assertEquals("clock must snap to zero at the scheduled reset frame",
				0.0, time.getClock().getFrame(), 0.0);

		for (int i = 0; i < 50; i++) tick.run();
		Assert.assertEquals("clock must continue counting from zero after the reset",
				50.0, time.getClock().getFrame(), 0.0);
	}

	/**
	 * The pure position function must agree with the live ticking clock at every
	 * checkpoint across multiple resets — it is the render-ahead producer's way of
	 * knowing the position the clock will hold at a future frame, so any
	 * divergence desynchronizes pattern content from automation.
	 */
	@Test(timeout = 120000)
	public void positionFunctionMatchesTickingClock() {
		GlobalTimeManager time = prepare(4);
		time.addReset(8);
		time.setup().get().run();
		Runnable tick = time.tick().get();

		Assert.assertEquals(399, time.positionForFrame(399));
		Assert.assertEquals(0, time.positionForFrame(400));
		Assert.assertEquals(50, time.positionForFrame(450));
		Assert.assertEquals(0, time.positionForFrame(800));
		Assert.assertEquals(200, time.positionForFrame(1000));

		long frame = 0;
		for (long checkpoint : new long[] {0, 399, 400, 450, 799, 800, 1000}) {
			while (frame < checkpoint) {
				tick.run();
				frame++;
			}
			Assert.assertEquals("the pure position must match the ticking clock"
							+ " at frame " + checkpoint,
					time.getClock().getFrame(),
					(double) time.positionForFrame(checkpoint), 0.0);
		}
	}

	/**
	 * The same reset must fire when the tick is compiled inside a {@code loop}
	 * advancing a whole buffer per run — the realtime PDSL runner's clock shape.
	 */
	@Test(timeout = 120000)
	public void resetFiresInsideCompiledLoop() {
		int bufferSize = 512;
		GlobalTimeManager time = prepare(20);
		Runnable buffer = loop(time.tick(), bufferSize).get();

		for (int i = 0; i < 4; i++) buffer.run();
		Assert.assertEquals("a reset scheduled mid-buffer must fire inside the"
						+ " compiled loop (2048 total frames, reset at 2000)",
				48.0, time.getClock().getFrame(), 0.0);
	}
}
