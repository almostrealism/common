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

package org.almostrealism.time.computations.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.computations.FourierTransform;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the conjugate symmetric extension of a half spectrum, both as a placement of
 * values and as the property it exists to guarantee: that the inverse transform of the
 * result carries no imaginary part.
 */
public class ConjugateSymmetryTests extends TestSuiteBase {

	/** Number of positive frequency bins in the half spectrum under test. */
	private static final int BINS = 4;

	/**
	 * A half spectrum whose real and imaginary parts are all distinct, so that a
	 * mirror which selected the wrong bin, or dropped the sign flip, is visible.
	 *
	 * @return the interleaved half spectrum, shaped (BINS, 2)
	 */
	private PackedCollection halfSpectrum() {
		return pack(1.0, 0.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0).reshape(shape(BINS, 2));
	}

	/**
	 * Bins below Nyquist are carried over, Nyquist is empty, and the bins above it
	 * are the conjugates of their counterparts in reverse order.
	 */
	@Test(timeout = 120000)
	public void mirrorsBinsAndNegatesTheImaginaryPart() {
		PackedCollection full = conjugateSymmetric(cp(halfSpectrum())).evaluate();

		Assert.assertEquals(2 * BINS, full.getShape().length(0));
		Assert.assertEquals(2, full.getShape().length(1));

		double expected[] = {
				1.0, 0.0,
				2.0, 3.0,
				4.0, 5.0,
				6.0, 7.0,
				0.0, 0.0,
				6.0, -7.0,
				4.0, -5.0,
				2.0, -3.0
		};

		double actual[] = full.toArray();

		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals("Element " + i, expected[i], actual[i], 1e-9);
		}
	}

	/**
	 * A sequence of spectra is extended one frame at a time, with no bin of one
	 * frame reaching into another.
	 */
	@Test(timeout = 120000)
	public void everyFrameIsExtendedIndependently() {
		PackedCollection frames = pack(
				1.0, 0.0, 2.0, 3.0,
				10.0, 0.0, 20.0, 30.0,
				100.0, 0.0, 200.0, 300.0).reshape(shape(3, 2, 2));

		PackedCollection full = conjugateSymmetric(cp(frames)).evaluate();

		Assert.assertEquals(3, full.getShape().length(0));
		Assert.assertEquals(4, full.getShape().length(1));
		Assert.assertEquals(2, full.getShape().length(2));

		double actual[] = full.toArray();

		for (int f = 0; f < 3; f++) {
			double scale = Math.pow(10.0, f);
			int base = f * 8;

			Assert.assertEquals("Frame " + f + " DC", 1.0 * scale, actual[base], 1e-9);
			Assert.assertEquals("Frame " + f + " DC phase", 0.0, actual[base + 1], 1e-9);
			Assert.assertEquals("Frame " + f + " bin 1", 2.0 * scale, actual[base + 2], 1e-9);
			Assert.assertEquals("Frame " + f + " bin 1 imaginary",
					3.0 * scale, actual[base + 3], 1e-9);
			Assert.assertEquals("Frame " + f + " Nyquist", 0.0, actual[base + 4], 1e-9);
			Assert.assertEquals("Frame " + f + " Nyquist imaginary", 0.0, actual[base + 5], 1e-9);
			Assert.assertEquals("Frame " + f + " mirrored bin", 2.0 * scale, actual[base + 6], 1e-9);
			Assert.assertEquals("Frame " + f + " mirrored bin imaginary",
					-3.0 * scale, actual[base + 7], 1e-9);
		}
	}

	/**
	 * The extension exists so that an inverse transform produces a real signal. A
	 * spectrum built from magnitudes and arbitrary phase, with the DC bin left real,
	 * should invert to samples whose imaginary parts vanish.
	 */
	@Test(timeout = 120000)
	public void inverseTransformIsRealValued() {
		int bins = 8;

		PackedCollection magnitude = pack(1.0, 0.6, 0.3, 0.9, 0.2, 0.7, 0.4, 0.5);
		PackedCollection angle = pack(0.0, 1.1, 2.4, 0.3, 2.9, 1.7, 0.8, 2.2);

		PackedCollection half = concat(1,
				cp(magnitude).multiply(cos(cp(angle))).reshape(shape(bins, 1)),
				cp(magnitude).multiply(sin(cp(angle))).reshape(shape(bins, 1)))
				.evaluate();

		PackedCollection spectrum = conjugateSymmetric(cp(half)).evaluate();

		FourierTransform inverse = new FourierTransform(1, 2 * bins, true, cp(spectrum));
		double samples[] = inverse.get().evaluate().toArray();

		for (int i = 0; i < 2 * bins; i++) {
			Assert.assertEquals("Sample " + i + " has an imaginary part",
					0.0, samples[i * 2 + 1], 1e-6);
		}
	}
}
