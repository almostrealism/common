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
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Verifies the contract of {@link AudioSceneOptimizer#defaultBreeder}: every parameter of
 * the offspring moves from the first parent toward the second, by the breeding scale or by
 * the whole distance between them if that is shorter.
 *
 * <p>The scale is drawn at random per call, so the assertions are the properties that hold
 * for any draw rather than exact values. The parents differ in every parameter, and differ
 * from each other by varying distances, so a combination that collapsed to a single value
 * and broadcast it could not satisfy them.</p>
 */
public class DefaultBreederTest extends TestSuiteBase implements CollectionFeatures {

	/** Number of parameters in the genomes under test. */
	private static final int PARAMETERS = 24;

	/** The breeding magnitude; the scale drawn per call lies in {@code [MAGNITUDE/2, MAGNITUDE)}. */
	private static final double MAGNITUDE = 0.02;

	/** Numerical tolerance, at the resolution of the device's storage. */
	private static final double EPS = 1.0e-6;

	/**
	 * Each offspring parameter must lie between its own two parents, and must not move
	 * further than the breeding magnitude allows.
	 */
	@Test(timeout = 60000)
	public void eachParameterMovesTowardItsOwnCounterpart() {
		// Parents that differ everywhere, by distances both above and below the magnitude,
		// so the clamped and unclamped branches are both exercised.
		PackedCollection first = linear(0.0, 0.6, PARAMETERS).evaluate().reshape(PARAMETERS);
		PackedCollection second = linear(0.9, 0.3, PARAMETERS).evaluate().reshape(PARAMETERS);

		PackedCollection offspring = ((ProjectedGenome) AudioSceneOptimizer
				.defaultBreeder(MAGNITUDE)
				.combine(new ProjectedGenome(first), new ProjectedGenome(second)))
				.getParameters();

		for (int i = 0; i < PARAMETERS; i++) {
			double a = first.toDouble(i);
			double b = second.toDouble(i);
			double o = offspring.toDouble(i);

			Assert.assertTrue("parameter " + i + " (" + o + ") must be between its parents "
							+ a + " and " + b,
					o >= Math.min(a, b) - EPS && o <= Math.max(a, b) + EPS);
			Assert.assertTrue("parameter " + i + " must not move further than the magnitude",
					Math.abs(o - a) <= MAGNITUDE + EPS);
			Assert.assertTrue("parameter " + i + " must move toward its counterpart",
					Math.abs(o - b) <= Math.abs(a - b) + EPS);
		}
	}

	/**
	 * The same pair must not keep producing the same child.
	 *
	 * <p>The scale is drawn per call, so breeding is a sampling of the space
	 * between two parents rather than a function of them. A pair that always
	 * bred the same offspring would make a population converge on whatever it
	 * started with, and would make repeated pairings of the good performers
	 * pointless.</p>
	 */
	@Test(timeout = 60000)
	public void theSamePairBreedsDifferentOffspring() {
		PackedCollection first = linear(0.0, 0.6, PARAMETERS).evaluate().reshape(PARAMETERS);
		PackedCollection second = linear(0.9, 0.3, PARAMETERS).evaluate().reshape(PARAMETERS);

		ProjectedGenome a = new ProjectedGenome(first);
		ProjectedGenome b = new ProjectedGenome(second);

		Set<String> signatures = new HashSet<>();
		for (int i = 0; i < 16; i++) {
			signatures.add(AudioSceneOptimizer.defaultBreeder(MAGNITUDE)
					.combine(a, b).signature());
		}

		Assert.assertTrue("Breeding one pair 16 times produced only "
						+ signatures.size() + " distinct offspring",
				signatures.size() > 1);
	}

	/**
	 * Parents closer together than the breeding scale breed the second parent
	 * exactly, and go on doing so however many times they are paired.
	 *
	 * <p>This is the one case where breeding is a function of its parents: the
	 * move is bounded by the distance between them, so once that distance is
	 * within reach of any scale the draw stops mattering. It is pinned here
	 * because it is the point at which a pairing stops contributing anything
	 * new, and because the identical signatures it produces are indistinguishable
	 * from a fault elsewhere.</p>
	 */
	@Test(timeout = 60000)
	public void parentsWithinTheScaleBreedTheSecondParentExactly() {
		// Every parameter differs by less than MAGNITUDE / 2, the smallest
		// scale that can be drawn, so every move is bounded by the distance.
		PackedCollection first = linear(0.40, 0.50, PARAMETERS).evaluate().reshape(PARAMETERS);
		PackedCollection second = linear(0.404, 0.504, PARAMETERS).evaluate().reshape(PARAMETERS);

		ProjectedGenome a = new ProjectedGenome(first);
		ProjectedGenome b = new ProjectedGenome(second);

		Set<String> signatures = new HashSet<>();
		for (int i = 0; i < 4; i++) {
			PackedCollection offspring = ((ProjectedGenome) AudioSceneOptimizer
					.defaultBreeder(MAGNITUDE).combine(a, b)).getParameters();
			signatures.add(new ProjectedGenome(offspring).signature());

			for (int j = 0; j < PARAMETERS; j++) {
				Assert.assertEquals("parameter " + j + " must arrive at the second parent",
						second.toDouble(j), offspring.toDouble(j), EPS);
			}
		}

		Assert.assertEquals("Converged parents breed one offspring, not several",
				1, signatures.size());
	}

	/** Identical parents must breed an offspring identical to them. */
	@Test(timeout = 60000)
	public void identicalParentsAreUnchanged() {
		PackedCollection parents = linear(0.1, 0.8, PARAMETERS).evaluate().reshape(PARAMETERS);

		PackedCollection offspring = ((ProjectedGenome) AudioSceneOptimizer
				.defaultBreeder(MAGNITUDE)
				.combine(new ProjectedGenome(parents), new ProjectedGenome(parents)))
				.getParameters();

		for (int i = 0; i < PARAMETERS; i++) {
			Assert.assertEquals("parameter " + i + " must be unchanged when the parents agree",
					parents.toDouble(i), offspring.toDouble(i), EPS);
		}
	}
}
