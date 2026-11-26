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
 * half the distance between them if that is shorter.
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
	 * Parents closer together than the breeding scale breed a new genome that lies
	 * strictly between them. It must never be a copy of either parent.
	 *
	 * <p>Before the move was capped at half the distance, such parents bred an
	 * exact copy of the second parent. With elitism, a population of one elite
	 * and one child then kept reproducing its non-elite member under the same
	 * signature, so a cycle produced nothing new. The offspring here is the
	 * midpoint of its parents, so it is the same for every draw of the scale.
	 * Being the same each time is expected here. Being a copy of a parent is not.</p>
	 */
	@Test(timeout = 60000)
	public void parentsWithinTheScaleBreedANewGenomeBetweenThem() {
		// Every parameter differs by less than MAGNITUDE / 2, the smallest
		// scale that can be drawn, so every move is bounded by the distance.
		PackedCollection first = linear(0.40, 0.50, PARAMETERS).evaluate().reshape(PARAMETERS);
		PackedCollection second = linear(0.404, 0.504, PARAMETERS).evaluate().reshape(PARAMETERS);

		ProjectedGenome a = new ProjectedGenome(first);
		ProjectedGenome b = new ProjectedGenome(second);

		for (int i = 0; i < 4; i++) {
			ProjectedGenome offspring = (ProjectedGenome) AudioSceneOptimizer
					.defaultBreeder(MAGNITUDE).combine(a, b);

			Assert.assertNotEquals("Close parents bred a copy of the second parent",
					b.signature(), offspring.signature());
			Assert.assertNotEquals("Close parents bred a copy of the first parent",
					a.signature(), offspring.signature());

			for (int j = 0; j < PARAMETERS; j++) {
				double lo = Math.min(first.toDouble(j), second.toDouble(j));
				double hi = Math.max(first.toDouble(j), second.toDouble(j));
				double o = offspring.getParameters().toDouble(j);

				Assert.assertTrue("parameter " + j + " (" + o + ") must lie strictly between "
						+ lo + " and " + hi, o > lo && o < hi);
				Assert.assertEquals("parameter " + j + " must move half way toward the second parent",
						(lo + hi) / 2, o, EPS);
			}
		}
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
