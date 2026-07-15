/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.studio.ml.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.arrange.MixdownManagerPdslAdapter;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that the {@link MixdownManagerPdslAdapter} constant-matrix / delay builders, after their
 * conversion from host {@code double[]} math to {@link org.almostrealism.collect.CollectionProducer}
 * graphs, still reproduce the original closed forms element-wise.
 */
public class MixdownAdapterConstantParityTest extends TestSuiteBase {

	/**
	 * A constant-valued diagonal matrix must be built with the
	 * {@link org.almostrealism.algebra.MatrixFeatures#identity(int)} CollectionProducer scaled by
	 * {@code value} — the graph-native form that replaced the removed host-side
	 * {@code diagonalMatrix(int, double)} builder (and the same {@code identity(n).multiply(...)}
	 * idiom {@code MixdownManagerPdslAdapter.householderMatrix} uses). It must carry {@code value}
	 * on the diagonal, zero elsewhere.
	 */
	@Test(timeout = 60000)
	public void diagonalMatrixMatchesReference() {
		int n = 5;
		double value = 0.75;

		PackedCollection matrix = identity(n).multiply(value).evaluate();

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double expected = (i == j) ? value : 0.0;
				Assert.assertEquals(expected, matrix.toDouble(i * n + j), 1e-6);
			}
		}
	}

	/** The producer-built scaled Householder reflection must match {@code gain*((i==j?1:0) - 2/n)}. */
	@Test(timeout = 60000)
	public void householderMatrixMatchesReference() {
		int n = 6;
		double gain = 1.0 / n;

		PackedCollection matrix = MixdownManagerPdslAdapter.householderMatrix(n, gain);

		double off = 2.0 / n;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double expected = gain * ((i == j ? 1.0 : 0.0) - off);
				Assert.assertEquals(expected, matrix.toDouble(i * n + j), 1e-6);
			}
		}
	}

	/**
	 * The producer-built reverb tap delays must equal the seconds-denominated
	 * golden-ratio spread the adapter now uses: {@code reverbTaps} lines (independent of
	 * the channel count), each at {@code floor(lo + frac((k + 1) * phi^-1) * (hi - lo))}
	 * where {@code lo = max(0.15 * sampleRate, signalSize)} and
	 * {@code hi = 1.5 * sampleRate} — the legacy DelayNetwork's 0.15–1.5 s per-line
	 * range, quasi-randomly but deterministically spaced. (An earlier revision of this
	 * test encoded the previous frames-denominated per-channel spread, which the reverb
	 * room redesign replaced.)
	 *
	 * <p>The device evaluates in float32 and the golden-ratio multiplier reaches ~20, so
	 * the fraction — and its floor — can land one sample either side of the
	 * double-precision truncation; a one-sample window on these multi-thousand-sample
	 * delays is the only allowed deviation.</p>
	 */
	@Test(timeout = 60000)
	public void reverbTapDelaysMatchReference() {
		int channels = 7;
		int signalSize = 1024;
		int sampleRate = 44100;
		MixdownManagerPdslAdapter.Config config =
				new MixdownManagerPdslAdapter.Config(channels, signalSize, sampleRate, 40, 1.0, 100);

		PackedCollection delays = MixdownManagerPdslAdapter.reverbTapDelays(config);

		int taps = MixdownManagerPdslAdapter.reverbTaps;
		double lo = Math.max(0.15 * sampleRate, signalSize);
		double hi = 1.5 * sampleRate;
		double phiInverse = 2.0 / (1.0 + Math.sqrt(5.0));
		for (int k = 0; k < taps; k++) {
			double fraction = ((k + 1) * phiInverse) % 1.0;
			double reference = (int) (lo + fraction * (hi - lo));
			double actual = delays.toDouble(k);
			Assert.assertTrue("delay[" + k + "]=" + actual + " expected " + reference + " within 1",
					Math.abs(actual - reference) <= 1.0);
		}
	}
}
