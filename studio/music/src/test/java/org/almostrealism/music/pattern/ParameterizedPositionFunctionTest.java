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

package org.almostrealism.music.pattern;

import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for {@link ParameterizedPositionFunction} and {@link ChordPositionFunction}
 * using fully deterministic {@link ParameterFunction}s, so every expected value is
 * derived from the documented formula rather than from a random draw.
 *
 * <p>A {@link ParameterFunction} built as {@code new ParameterFunction(0, 0, 0, c)}
 * ignores its parameters and returns {@code sin(2 pi c)}.</p>
 */
public class ParameterizedPositionFunctionTest extends TestSuiteBase {

	/** Tolerance for comparisons of trigonometric results. */
	private static final double EPSILON = 1e-9;

	/** Parameters that have no influence on a constant {@link ParameterFunction}. */
	private static final ParameterSet PARAMS = new ParameterSet(0.3, 0.6, 0.9);

	/**
	 * Returns a {@link ParameterFunction} that ignores its parameters and evaluates
	 * to {@code value}, which must lie in {@code [-1, 1]}.
	 *
	 * @param value the constant result
	 * @return the constant function
	 */
	static ParameterFunction constantFunction(double value) {
		return new ParameterFunction(0, 0, 0, Math.asin(value) / (2 * Math.PI));
	}

	/**
	 * Returns a {@link ParameterizedPositionFunction} that evaluates to exactly
	 * {@code value} at every whole-measure position, for any scale.
	 *
	 * <p>With zero regularity, regularity offset and rate, a whole-measure position
	 * regularizes to {@code 0}, leaving {@code sin(8 pi o)} where {@code o} is the
	 * rate offset. Choosing {@code o = asin(value) / (8 pi)} yields {@code value}.</p>
	 *
	 * @param value the result in {@code [-1, 1]}
	 * @return the position function
	 */
	static ParameterizedPositionFunction constantAtWholeMeasures(double value) {
		return new ParameterizedPositionFunction(constantFunction(0), constantFunction(0), constantFunction(0),
				constantFunction(Math.asin(value) / (8 * Math.PI)));
	}

	/** At whole-measure positions the function reduces to its rate offset term. */
	@Test(timeout = 10000)
	public void wholeMeasuresReduceToRateOffset() {
		ParameterizedPositionFunction f = constantAtWholeMeasures(0.4);

		for (double position : new double[] { 0.0, 1.0, 3.0, -2.0 }) {
			Assert.assertEquals("position " + position, 0.4, f.apply(PARAMS, position, 0.5), EPSILON);
		}

		ParameterizedPositionFunction negative = constantAtWholeMeasures(-0.7);
		Assert.assertEquals(-0.7, negative.apply(PARAMS, 2.0, 1.0), EPSILON);
		Assert.assertEquals("applyPositive returns the magnitude",
				0.7, negative.applyPositive(PARAMS, 2.0, 1.0), EPSILON);
	}

	/**
	 * With zero regularity the positional quantization divides by {@code 16 * scale},
	 * and the rate term contributes {@code 1024 * position * (2 + rate)} half-turns.
	 */
	@Test(timeout = 10000)
	public void regularizationFollowsDocumentedFormula() {
		ParameterizedPositionFunction f = new ParameterizedPositionFunction(
				constantFunction(0), constantFunction(0), constantFunction(0.5), constantFunction(0));

		double position = 0.3001;
		double scale = 1.0;
		double regularized = (position / (16 * scale)) % 1.0;
		double expected = Math.sin(Math.PI * 1024 * regularized * 2.5);
		Assert.assertEquals("sin(48.016 pi)", Math.sin(0.016 * Math.PI), expected, 1e-6);

		Assert.assertEquals(expected, f.apply(PARAMS, position, scale), EPSILON);
		Assert.assertEquals("the pre-regularized form uses the same rate term",
				expected, f.apply(PARAMS, regularized), EPSILON);
	}

	/** Positions that differ by a whole number of measures are indistinguishable. */
	@Test(timeout = 10000)
	public void positionIsPeriodicInWholeMeasures() {
		ParameterizedPositionFunction f = new ParameterizedPositionFunction(
				constantFunction(0.9), constantFunction(0.1), constantFunction(-0.3), constantFunction(0.2));

		Assert.assertEquals(f.apply(PARAMS, 0.37, 0.25), f.apply(PARAMS, 5.37, 0.25), EPSILON);
		Assert.assertEquals(f.apply(PARAMS, 0.81, 0.125), f.apply(PARAMS, -1.19, 0.125), EPSILON);
	}

	/** A regularity offset shifts the position before quantization. */
	@Test(timeout = 10000)
	public void regularityOffsetShiftsPosition() {
		double offset = 0.2;
		ParameterizedPositionFunction shifted = new ParameterizedPositionFunction(
				constantFunction(0), constantFunction(offset), constantFunction(0.5), constantFunction(0.1));
		ParameterizedPositionFunction unshifted = new ParameterizedPositionFunction(
				constantFunction(0), constantFunction(0), constantFunction(0.5), constantFunction(0.1));

		Assert.assertEquals(unshifted.apply(PARAMS, 0.45 + offset, 0.5),
				shifted.apply(PARAMS, 0.45, 0.5), EPSILON);
	}

	/** The randomly initialized function populates every component and stays in [-1, 1]. */
	@Test(timeout = 10000)
	public void randomFunctionIsBounded() {
		ParameterizedPositionFunction f = ParameterizedPositionFunction.random();
		Assert.assertNotNull(f.getRegularity());
		Assert.assertNotNull(f.getRegularityOffset());
		Assert.assertNotNull(f.getRate());
		Assert.assertNotNull(f.getRateOffset());

		for (int i = 0; i < 32; i++) {
			double v = f.apply(PARAMS, i * 0.37, 0.25);
			Assert.assertTrue("value " + v + " is outside [-1, 1]", v >= -1.0 && v <= 1.0);
			Assert.assertEquals(Math.abs(v), f.applyPositive(PARAMS, i * 0.37, 0.25), 0.0);
		}
	}

	/** Setters replace each component function. */
	@Test(timeout = 10000)
	public void settersReplaceComponents() {
		ParameterizedPositionFunction f = new ParameterizedPositionFunction();
		ParameterFunction a = constantFunction(0.1);
		ParameterFunction b = constantFunction(0.2);
		ParameterFunction c = constantFunction(0.3);
		ParameterFunction d = constantFunction(0.4);
		f.setRegularity(a);
		f.setRegularityOffset(b);
		f.setRate(c);
		f.setRateOffset(d);

		Assert.assertSame(a, f.getRegularity());
		Assert.assertSame(b, f.getRegularityOffset());
		Assert.assertSame(c, f.getRate());
		Assert.assertSame(d, f.getRateOffset());
	}

	/** Each chord depth is evaluated by its own position function, as a magnitude. */
	@Test(timeout = 10000)
	public void chordPositionsAreEvaluatedPerDepth() {
		ChordPositionFunction chord = new ChordPositionFunction();
		Assert.assertTrue(chord.getScalePositions().isEmpty());
		Assert.assertTrue(chord.applyAll(PARAMS, 1.0, 1.0, 0).isEmpty());

		chord.setScalePositions(List.of(
				constantAtWholeMeasures(0.1),
				constantAtWholeMeasures(-0.5),
				constantAtWholeMeasures(0.9)));

		Assert.assertEquals(0.5, chord.apply(PARAMS, 2.0, 1.0, 1), EPSILON);

		List<Double> all = chord.applyAll(PARAMS, 2.0, 1.0, 3);
		Assert.assertEquals(3, all.size());
		Assert.assertEquals(0.1, all.get(0), EPSILON);
		Assert.assertEquals(0.5, all.get(1), EPSILON);
		Assert.assertEquals(0.9, all.get(2), EPSILON);

		Assert.assertEquals(2, chord.applyAll(PARAMS, 2.0, 1.0, 2).size());
	}

	/** A random chord function supports every depth up to the documented maximum. */
	@Test(timeout = 10000)
	public void randomChordFunctionSupportsMaxDepth() {
		ChordPositionFunction chord = ChordPositionFunction.random();
		Assert.assertEquals(ChordPositionFunction.MAX_CHORD_DEPTH, chord.getScalePositions().size());

		List<Double> positions = chord.applyAll(PARAMS, 0.5, 0.25,
				ChordPositionFunction.MAX_CHORD_DEPTH);
		positions.forEach(p -> Assert.assertTrue("position " + p + " is outside [0, 1]",
				p >= 0.0 && p <= 1.0));

		try {
			chord.apply(PARAMS, 0.5, 0.25, ChordPositionFunction.MAX_CHORD_DEPTH);
			Assert.fail("depth beyond the maximum has no position function");
		} catch (IndexOutOfBoundsException expected) {
			Assert.assertEquals(ChordPositionFunction.MAX_CHORD_DEPTH, chord.getScalePositions().size());
		}
	}
}
