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

package org.almostrealism.audio.notes.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.notes.ReversePlaybackAudioFilter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Receipts for {@link ReversePlaybackAudioFilter}: the filter must return the
 * input samples in reverse order with the input's shape preserved, for any
 * input length, including consecutive applications at different lengths (the
 * underlying reversal kernel is compiled once and shared across all notes).
 */
public class ReversePlaybackAudioFilterTest extends TestSuiteBase {

	/**
	 * Reverses a small ramp and checks every output sample, so an off-by-one in
	 * the reversal indexing (reading one past the end, or dropping the first
	 * sample) fails loudly rather than as noise at the end of a note.
	 */
	@Test(timeout = 120000)
	public void reversesSamplesExactly() {
		assertReversed(8);
	}

	/**
	 * Applies the shared reversal to inputs of several different lengths in one
	 * session, verifying the compiled kernel serves every note length rather
	 * than collapsing to the shape of its first evaluation.
	 */
	@Test(timeout = 120000)
	public void reversesAcrossLengths() {
		assertReversed(1000);
		assertReversed(16);
		assertReversed(44100);
		assertReversed(3);
	}

	/**
	 * Builds a ramp of the given length, applies the filter, and asserts the
	 * output is the exact reverse with shape preserved.
	 *
	 * @param frames the input length in samples
	 */
	private void assertReversed(int frames) {
		ReversePlaybackAudioFilter filter = new ReversePlaybackAudioFilter();

		PackedCollection input = new PackedCollection(frames);
		integers(0, frames).add(c(1.0)).into(input.traverseEach()).evaluate();

		Producer<PackedCollection> reversed =
				filter.apply(p(input), c(1.0), c(1.0));
		PackedCollection out = reversed.get().evaluate();

		Assert.assertEquals("Reversed output must preserve the input length",
				frames, out.getShape().getTotalSize());

		double[] values = out.toArray(0, frames);
		for (int i = 0; i < frames; i++) {
			Assert.assertEquals("Reversed sample " + i + " of " + frames,
					frames - i, values[i], 1e-6);
		}

		input.destroy();
	}
}
