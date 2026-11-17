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

package org.almostrealism.audio.filter.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.filter.AudioSumProvider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.stream.IntStream;

/**
 * Verifies that {@link AudioSumProvider} performs its in-place sum and volume
 * operations correctly in both sequential (repeated) and parallel (kernel) modes.
 *
 * <p>The sequential mode exercises {@code toRepeated()}, whose adapter previously
 * zeroed output element 0 before the loop started. Because {@link AudioSumProvider}
 * evaluates in place (the destination buffer is also the first operand), that
 * zeroing clobbered frame 0 of the input before the loop read it, so frame 0 came
 * out {@code 0.0}. These tests pin a non-zero value into frame 0 so the defect
 * surfaces as a mismatch there.
 */
public class AudioSumProviderTest extends TestSuiteBase implements CellFeatures {

	/** Number of frames in the synthetic buffers. */
	private static final int LEN = 8;

	/**
	 * Sequential mode: both the sum and the volume scaling must preserve frame 0.
	 */
	@Test(timeout = 120000)
	public void sequentialSumAndVolume() {
		verifySumAndVolume(new AudioSumProvider(false));
	}

	/**
	 * Parallel (kernel) mode: the same results, through the non-repeated path.
	 */
	@Test(timeout = 120000)
	public void parallelSumAndVolume() {
		verifySumAndVolume(new AudioSumProvider(true));
	}

	/**
	 * Runs an in-place sum and an in-place volume scaling on fresh buffers whose
	 * frame 0 is non-zero, and asserts every frame (frame 0 in particular) holds
	 * the expected result.
	 *
	 * @param provider the provider under test
	 */
	private void verifySumAndVolume(AudioSumProvider provider) {
		PackedCollection dest = new PackedCollection(LEN).fill(1.0);
		PackedCollection in = new PackedCollection(LEN).fill(2.0);

		provider.sum(dest, in);

		IntStream.range(0, LEN).forEach(i ->
				assertEquals("sum frame " + i, 3.0, dest.toDouble(i)));

		PackedCollection scaled = new PackedCollection(LEN).fill(1.0);
		PackedCollection volume = new PackedCollection(1).fill(0.5);

		provider.adjustVolume(scaled, volume);

		IntStream.range(0, LEN).forEach(i ->
				assertEquals("volume frame " + i, 0.5, scaled.toDouble(i)));
	}
}
