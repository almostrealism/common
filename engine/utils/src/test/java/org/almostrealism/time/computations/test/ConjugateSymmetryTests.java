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

		PackedCollection expected = pack(
				1.0, 0.0,
				2.0, 3.0,
				4.0, 5.0,
				6.0, 7.0,
				0.0, 0.0,
				6.0, -7.0,
				4.0, -5.0,
				2.0, -3.0
		).reshape(full.getShape());

		Assert.assertEquals("The extended spectrum must mirror its bins",
				0.0, largestDeviation(expected, full), 1e-9);
	}

	/**
	 * A sequence of spectra is extended one frame at a time, with no bin of one
	 * frame reaching into another.
	 *
	 * <p>Every frame extends to the same pattern — DC, bin 1, a zero Nyquist, then bin 1
	 * mirrored with its imaginary part negated — scaled by that frame's own magnitude.
	 * Expressing the expectation as that pattern against those scales is what puts
	 * independence under test: a bin reaching into a neighbouring frame would carry the
	 * wrong scale and show up as a deviation.</p>
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

		PackedCollection pattern = pack(1.0, 0.0, 2.0, 3.0, 0.0, 0.0, 2.0, -3.0);
		PackedCollection scales = pack(1.0, 10.0, 100.0);

		PackedCollection expected =
				repeat(0, 3, cp(pattern).reshape(shape(1, 8)))
						.multiply(repeat(1, 8, cp(scales).reshape(shape(3, 1))))
						.reshape(full.getShape())
						.evaluate();

		Assert.assertEquals("Each frame must extend independently",
				0.0, largestDeviation(expected, full), 1e-9);
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
		PackedCollection samples = inverse.get().evaluate();

		// The imaginary parts are the second component of each sample, so they are the
		// far column of the [samples, 2] result rather than a strided read of the whole.
		PackedCollection imaginary = subset(shape(2 * bins, 1),
				cp(samples).reshape(shape(2 * bins, 2)), 0, 1).evaluate();

		Assert.assertEquals("The inverse transform must be real valued",
				0.0, largestDeviation(0.0, imaginary), 1e-6);
	}
}
