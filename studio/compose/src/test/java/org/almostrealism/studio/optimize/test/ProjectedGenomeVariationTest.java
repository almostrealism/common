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

package org.almostrealism.studio.optimize.test;

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the mutation contract of {@link ProjectedGenome#variation}: parameters are
 * perturbed with the given probability and the result is clamped to the permitted range.
 *
 * <p>Each case pins the mutation rate to an extreme so the outcome does not depend on
 * the random draw — at a rate of zero no parameter may change, and at a rate of one
 * every parameter must. The deltas are large enough that a mutated parameter always
 * lands outside the range, which makes the clamped result exact.</p>
 */
public class ProjectedGenomeVariationTest extends TestSuiteBase implements CollectionFeatures {

	/** Number of parameters in the genomes under test. */
	private static final int PARAMETERS = 32;

	/** The value every parameter starts at. */
	private static final double ORIGINAL = 0.5;

	/** Numerical tolerance for floating-point comparisons. */
	private static final double EPS = 1.0e-9;

	/**
	 * Builds a genome whose parameters are all {@link #ORIGINAL}.
	 *
	 * @return the genome
	 */
	private ProjectedGenome genome() {
		return new ProjectedGenome(new PackedCollection(PARAMETERS).fill(ORIGINAL));
	}

	/**
	 * A constant delta for every parameter.
	 *
	 * @param value the delta to apply
	 * @return the deltas, shaped {@code [PARAMETERS]}
	 */
	private PackedCollection delta(double value) {
		return new PackedCollection(PARAMETERS).fill(value);
	}

	/** A mutation rate of zero must leave every parameter untouched. */
	@Test(timeout = 60000)
	public void zeroRateLeavesParametersUnchanged() {
		double[] result = genome().variation(0.0, 1.0, 0.0, cp(delta(10.0)))
				.getParameters().toArray(0, PARAMETERS);

		for (int i = 0; i < PARAMETERS; i++) {
			Assert.assertEquals("parameter " + i + " must be unchanged at a rate of zero",
					ORIGINAL, result[i], EPS);
		}
	}

	/** A mutation rate of one, with a delta that overshoots, must clamp to the maximum. */
	@Test(timeout = 60000)
	public void fullRateClampsToMaximum() {
		double[] result = genome().variation(0.0, 1.0, 1.0, cp(delta(10.0)))
				.getParameters().toArray(0, PARAMETERS);

		for (int i = 0; i < PARAMETERS; i++) {
			Assert.assertEquals("parameter " + i + " must be clamped to the maximum",
					1.0, result[i], EPS);
		}
	}

	/** A negative delta that undershoots must clamp to the minimum. */
	@Test(timeout = 60000)
	public void fullRateClampsToMinimum() {
		double[] result = genome().variation(0.0, 1.0, 1.0, cp(delta(-10.0)))
				.getParameters().toArray(0, PARAMETERS);

		for (int i = 0; i < PARAMETERS; i++) {
			Assert.assertEquals("parameter " + i + " must be clamped to the minimum",
					0.0, result[i], EPS);
		}
	}

	/** A delta that stays inside the range must be applied without clamping. */
	@Test(timeout = 60000)
	public void inRangeDeltaIsAppliedExactly() {
		double[] result = genome().variation(0.0, 1.0, 1.0, cp(delta(0.25)))
				.getParameters().toArray(0, PARAMETERS);

		for (int i = 0; i < PARAMETERS; i++) {
			Assert.assertEquals("parameter " + i + " must carry the delta",
					ORIGINAL + 0.25, result[i], EPS);
		}
	}

	/** The original genome must not be modified by producing a variation of it. */
	@Test(timeout = 60000)
	public void originalGenomeIsUnmodified() {
		ProjectedGenome original = genome();
		original.variation(0.0, 1.0, 1.0, cp(delta(10.0)));

		double[] parameters = original.getParameters().toArray(0, PARAMETERS);
		for (int i = 0; i < PARAMETERS; i++) {
			Assert.assertEquals("the original parameter " + i + " must be untouched",
					ORIGINAL, parameters[i], EPS);
		}
	}
}
