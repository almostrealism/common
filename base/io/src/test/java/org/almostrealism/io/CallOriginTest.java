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

package org.almostrealism.io;

import org.junit.Assert;
import org.junit.Test;

/**
 * Pins which frames {@link CallOrigin} names: the origin is the first frame outside both the
 * reporting class and {@link CallOrigin} itself, and the callers are the frames above it, never
 * the origin again, never the reporter's own, and never more than requested.
 */
public class CallOriginTest {

	/** A class making a report, which reaches the capture through several of its own frames. */
	private static class Reporter {
		/**
		 * Captures an origin after passing through {@code depth} further frames of this class.
		 *
		 * @param depth  the number of additional reporter frames to pass through
		 * @param frames the number of caller frames to request
		 * @return the captured origin
		 */
		CallOrigin report(int depth, int frames) {
			return depth == 0 ? new CallOrigin(Reporter.class, frames) : report(depth - 1, frames);
		}
	}

	/**
	 * The call site being reported, one frame below the test method.
	 *
	 * @param depth  the number of additional reporter frames
	 * @param frames the number of caller frames to request
	 * @return the captured origin
	 */
	private static CallOrigin reportFromHere(int depth, int frames) {
		return new Reporter().report(depth, frames);
	}

	/** The origin skips every frame of the reporter and of the capture, however deep. */
	@Test(timeout = 10000)
	public void originIsFirstFrameOutsideReporter() {
		for (int depth = 0; depth < 4; depth++) {
			String origin = reportFromHere(depth, 4).getOrigin();
			Assert.assertTrue(origin, origin.startsWith(CallOriginTest.class.getName() + ".reportFromHere("));
		}
	}

	/** The callers begin above the origin, starting with the test method that made the call. */
	@Test(timeout = 10000)
	public void callersBeginAboveOrigin() {
		String callers = reportFromHere(2, 1).getCallers();
		Assert.assertTrue(callers, callers.startsWith("\n    at " + CallOriginTest.class.getName()
				+ ".callersBeginAboveOrigin("));
		Assert.assertFalse(callers, callers.contains("reportFromHere"));
		Assert.assertFalse(callers, callers.contains(Reporter.class.getName()));
		Assert.assertFalse(callers, callers.contains(CallOrigin.class.getName() + "."));
	}

	/** Exactly the requested number of callers is shown, one per line, and none for zero. */
	@Test(timeout = 10000)
	public void callersLimitedToRequestedFrames() {
		Assert.assertEquals("", reportFromHere(0, 0).getCallers());

		for (int frames = 1; frames <= 3; frames++) {
			String callers = reportFromHere(1, frames).getCallers();
			Assert.assertEquals(callers, frames, callers.split("\n    at ", -1).length - 1);
		}
	}
}
