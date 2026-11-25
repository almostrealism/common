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

package org.almostrealism.music.pattern.test;

import org.almostrealism.music.pattern.PatternRenderCancellation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CancellationException;

/**
 * Verifies the thread-bound cooperative cancellation signal
 * {@link PatternRenderCancellation} used to stop an in-progress pattern render: the
 * instance request flag, its thread binding, and the ambient {@code static} polls a
 * render reaches without a reference to the signal.
 */
public class PatternRenderCancellationTest extends TestSuiteBase {

	/**
	 * A thread with no bound signal is never cancelled and its {@link
	 * PatternRenderCancellation#requireNotCancelled(int)} poll does not throw.
	 */
	@Test(timeout = 30_000)
	public void unboundThreadIsNeverCancelled() {
		Assert.assertFalse(PatternRenderCancellation.currentlyCancelled());
		// Must not throw: no signal is bound to this thread.
		PatternRenderCancellation.requireNotCancelled(0);
	}

	/**
	 * The request flag is sticky and instance-scoped, readable before and after a binding.
	 */
	@Test(timeout = 30_000)
	public void cancelSetsStickyInstanceRequest() {
		PatternRenderCancellation signal = new PatternRenderCancellation();
		Assert.assertFalse("a fresh signal is not cancelled", signal.isCancelled());

		signal.cancel();
		Assert.assertTrue("cancel() requests cancellation", signal.isCancelled());
		signal.cancel();
		Assert.assertTrue("the request stays set after a second cancel()", signal.isCancelled());
	}

	/**
	 * A bound-but-uncancelled signal reports no cancellation through the ambient polls.
	 */
	@Test(timeout = 30_000)
	public void boundButNotCancelledIsNotCancelled() {
		PatternRenderCancellation signal = new PatternRenderCancellation();
		signal.bind();
		try {
			Assert.assertFalse(PatternRenderCancellation.currentlyCancelled());
			PatternRenderCancellation.requireNotCancelled(100);
		} finally {
			signal.unbind();
		}
	}

	/**
	 * Cancelling a bound signal is observed through the ambient poll, and {@link
	 * PatternRenderCancellation#requireNotCancelled(int)} throws naming the frame reached.
	 */
	@Test(timeout = 30_000)
	public void cancelledBoundSignalIsObservedAmbiently() {
		PatternRenderCancellation signal = new PatternRenderCancellation();
		signal.bind();
		try {
			signal.cancel();
			Assert.assertTrue(PatternRenderCancellation.currentlyCancelled());

			try {
				PatternRenderCancellation.requireNotCancelled(512);
				Assert.fail("requireNotCancelled should throw once cancelled");
			} catch (CancellationException e) {
				Assert.assertTrue("message should name the frame reached: " + e.getMessage(),
						e.getMessage().contains("frame 512"));
			}
		} finally {
			signal.unbind();
		}
	}

	/**
	 * {@link PatternRenderCancellation#unbind()} clears only this thread's binding when it is
	 * this signal, leaving a different signal's binding in place.
	 */
	@Test(timeout = 30_000)
	public void unbindOnlyClearsOwnBinding() {
		PatternRenderCancellation bound = new PatternRenderCancellation();
		PatternRenderCancellation other = new PatternRenderCancellation();
		bound.bind();
		try {
			bound.cancel();

			// A different signal's unbind must not disturb the bound one.
			other.unbind();
			Assert.assertTrue("a foreign unbind must not clear the current binding",
					PatternRenderCancellation.currentlyCancelled());

			bound.unbind();
			Assert.assertFalse("unbinding the current signal clears the thread",
					PatternRenderCancellation.currentlyCancelled());
		} finally {
			bound.unbind();
		}
	}
}
