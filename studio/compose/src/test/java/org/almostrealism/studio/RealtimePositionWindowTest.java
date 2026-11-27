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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the position arithmetic of {@link RealtimePositionWindow}.
 */
public class RealtimePositionWindowTest extends TestSuiteBase {

	/** The default window reproduces the raw cursor, so a runner without options is unchanged. */
	@Test(timeout = 10000)
	public void defaultWindowIsIdentity() {
		RealtimePositionWindow window = new RealtimePositionWindow();
		for (long f = 0; f < 1_000_000; f += 1024) {
			Assert.assertEquals(f, window.position(f));
		}
	}

	/** Positions wrap at an integer loop length over several passes. */
	@Test(timeout = 10000)
	public void positionsWrapAtLoopLength() {
		RealtimePositionWindow window = new RealtimePositionWindow(0, 4096);
		Assert.assertEquals(0, window.position(4096));
		Assert.assertEquals(1024, window.position(4096 * 3 + 1024));
	}

	/**
	 * A fractional loop length wraps at the unrounded value, so the positions follow a player
	 * read head that wraps at the same fractional length rather than drifting by a frame per pass.
	 */
	@Test(timeout = 10000)
	public void fractionalLoopDoesNotDrift() {
		double loop = 1000.25;
		RealtimePositionWindow window = new RealtimePositionWindow(0, loop);
		// 40 passes of 1000.25 frames end at frame 40010, not 40000
		Assert.assertEquals(490, window.position(40_500));
		Assert.assertEquals(0, window.position(40_010));
		Assert.assertEquals(0, window.position(4001));
	}

	/** The start frame offsets the cursor and is itself wrapped into the loop. */
	@Test(timeout = 10000)
	public void startFrameOffsetsAndWraps() {
		RealtimePositionWindow window = new RealtimePositionWindow(5000, 4096);
		Assert.assertEquals(904, window.getStartFrame());
		Assert.assertEquals(904, window.position(0));
		Assert.assertEquals((904 + 4000) % 4096, window.position(4000));

		window.setLoopFrames(0);
		window.setStartFrame(5000);
		Assert.assertEquals(6024, window.position(1024));
	}
}
