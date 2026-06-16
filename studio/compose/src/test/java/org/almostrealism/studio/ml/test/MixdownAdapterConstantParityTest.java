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

import java.lang.reflect.Method;

/**
 * Verifies that the {@link MixdownManagerPdslAdapter} constant-matrix / delay builders, after their
 * conversion from host {@code double[]} math to {@link org.almostrealism.collect.CollectionProducer}
 * graphs, still reproduce the original closed forms element-wise.
 */
public class MixdownAdapterConstantParityTest extends TestSuiteBase {

	/** The producer-built diagonal matrix must carry {@code value} on the diagonal, zero elsewhere. */
	@Test(timeout = 60000)
	public void diagonalMatrixMatchesReference() throws Exception {
		int n = 5;
		double value = 0.75;

		Method method = MixdownManagerPdslAdapter.class.getDeclaredMethod(
				"diagonalMatrix", int.class, double.class);
		method.setAccessible(true);
		PackedCollection matrix = (PackedCollection) method.invoke(null, n, value);

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double expected = (i == j) ? value : 0.0;
				Assert.assertEquals(expected, matrix.toDouble(i * n + j), 1e-6);
			}
		}
	}

	/** The producer-built scaled Householder reflection must match {@code gain*((i==j?1:0) - 2/n)}. */
	@Test(timeout = 60000)
	public void householderMatrixMatchesReference() throws Exception {
		int n = 6;
		double gain = 1.0 / n;

		Method method = MixdownManagerPdslAdapter.class.getDeclaredMethod(
				"householderMatrix", int.class, double.class);
		method.setAccessible(true);
		PackedCollection matrix = (PackedCollection) method.invoke(null, n, gain);

		double off = 2.0 / n;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double expected = gain * ((i == j ? 1.0 : 0.0) - off);
				Assert.assertEquals(expected, matrix.toDouble(i * n + j), 1e-6);
			}
		}
	}

	/** The producer-built reverb tap delays must equal {@code floor(fraction*ring)} per channel. */
	@Test(timeout = 60000)
	public void reverbTapDelaysMatchReference() throws Exception {
		int channels = 7;
		int signalSize = 1024;
		int reverbFrames = 2;
		MixdownManagerPdslAdapter.Config config =
				new MixdownManagerPdslAdapter.Config(channels, signalSize, 44100, 40, 1.0, 100);

		Method method = MixdownManagerPdslAdapter.class.getDeclaredMethod(
				"reverbTapDelays", MixdownManagerPdslAdapter.Config.class);
		method.setAccessible(true);
		PackedCollection delays = (PackedCollection) method.invoke(null, config);

		int ring = reverbFrames * signalSize;
		for (int ch = 0; ch < channels; ch++) {
			double fraction = 0.3 + 0.55 * (ch + 1.0) / (channels + 1.0);
			double reference = (int) (fraction * ring);
			double actual = delays.toDouble(ch);
			// floor() is evaluated in device float32, so at an exact integer boundary it can land one
			// below the double-precision (int) truncation; off-by-one there is the only allowed deviation.
			Assert.assertTrue("delay[" + ch + "]=" + actual + " expected " + reference + " or one below",
					actual == reference || actual == reference - 1.0);
		}
	}
}
