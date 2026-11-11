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

package org.almostrealism.util;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ProducerWithRank;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.computations.ProducerWithRankAdapter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.computations.RankedChoiceEvaluable;
import org.almostrealism.geometry.computations.RankedChoiceEvaluableForVector;
import org.almostrealism.hardware.Hardware;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

/**
 * Tests for RankedChoiceEvaluable.
 */
public class RankedChoiceEvaluableTest extends TestSuiteBase {
	/**
	 * Tests the highest rank operation.
	 */
	@Test(timeout = 10000)
	@TestProperties(knownIssue = true)
	public void highestRank() {

		IntStream.range(0, 5).forEach(i -> {
			PackedCollection in = pack(1.0);
			Pair out = RankedChoiceEvaluable.highestRank.evaluate(
					in, new Pair(3, Intersection.e));

			log("rank = " + out.toDouble(0));
			assertEquals(1.0, out.toDouble(0));
		});
	}

	/**
	 * Tests the highest rank kernel operation.
	 */
	@Test(timeout = 10000)
	public void highestRankKernel() {
		PackedCollection in = pack(0.0, 2.0, 1.0, 3.0).reshape(new TraversalPolicy(4, 1));

		PackedCollection out = Pair.bank(1);

		PackedCollection conf = Pair.bank(1);
		conf.set(0, new Pair(4, Intersection.e));

		RankedChoiceEvaluable.highestRank.into(out).evaluate(in, conf);

		out.get(0).print();
		Assert.assertEquals(1.0, out.get(0).toDouble(0), Math.pow(10, -10));
	}

	/**
	 * Returns a RankedChoiceProducer with three ranked choices.
	 */
	protected RankedChoiceEvaluableForVector getRankedChoiceProducer1() {
		ProducerWithRankAdapter<PackedCollection> v1 = new ProducerWithRankAdapter<>(vector(1, 2, 3), c(2.0));
		ProducerWithRankAdapter<PackedCollection> v2 = new ProducerWithRankAdapter<>(vector(4, 5, 6), c(1.0));
		ProducerWithRankAdapter<PackedCollection> v3 = new ProducerWithRankAdapter<>(vector(7, 8, 9), c(3.0));

		RankedChoiceEvaluableForVector rcp = new RankedChoiceEvaluableForVector(Intersection.e);
		rcp.add((ProducerWithRank) v1);
		rcp.add((ProducerWithRank) v2);
		rcp.add((ProducerWithRank) v3);
		return rcp;
	}

	/**
	 * Returns a RankedChoiceProducer with two ranked choices.
	 */
	protected RankedChoiceEvaluableForVector getRankedChoiceProducer2() {
		ProducerWithRankAdapter<PackedCollection> v1 = new ProducerWithRankAdapter<>(vector(0.7034, 0.7034, 0.7034), c(0.9002));
		ProducerWithRankAdapter<PackedCollection> v2 = new ProducerWithRankAdapter<>(vector(0.0, 0.0, 0.0), c(-17.274));

		RankedChoiceEvaluableForVector rcp = new RankedChoiceEvaluableForVector(Intersection.e);
		rcp.add((ProducerWithRank) v1);
		rcp.add((ProducerWithRank) v2);
		return rcp;
	}

	/**
	 * Tests ranked choice selection with first producer.
	 */
	// TODO  @Test(timeout = 10000)
	public void rankedChoice1() {
		RankedChoiceEvaluableForVector rcp = getRankedChoiceProducer1();
		Evaluable<Vector> acc = rcp.getAccelerated();

		Vector result = acc.evaluate();
		log("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}

	/**
	 * Tests ranked choice selection with second producer.
	 */
	// TODO  @Test(timeout = 10000)
	public void rankedChoice2() {
		RankedChoiceEvaluableForVector rcp = getRankedChoiceProducer2();
		Evaluable<Vector> acc = rcp.getAccelerated();

		Vector result = acc.evaluate();
		log("result = " + result);
		assert result.equals(new Vector(0.7034, 0.7034, 0.7034));
	}

	/**
	 * Tests compact ranked choice selection with first producer.
	 */
	// TODO  @Test(timeout = 10000)
	public void rankedChoiceCompact1() {
		RankedChoiceEvaluableForVector rcp = getRankedChoiceProducer1();
		Evaluable<Vector> acc = rcp.getAccelerated();

		Vector result = acc.evaluate();
		log("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}

	/**
	 * Returns a {@link RankedChoiceEvaluableForVector} whose candidates are the given values,
	 * in order, each with the matching rank.
	 */
	protected RankedChoiceEvaluableForVector rankedChoice(double[][] values, double... ranks) {
		RankedChoiceEvaluableForVector rcp = new RankedChoiceEvaluableForVector(Intersection.e);

		for (int i = 0; i < ranks.length; i++) {
			rcp.add((ProducerWithRank) new ProducerWithRankAdapter<>(
					vector(values[i][0], values[i][1], values[i][2]), c(ranks[i])));
		}

		return rcp;
	}

	/** Evaluates {@link RankedChoiceEvaluableForVector#getClosestChoice(int)} and checks the result. */
	protected void assertClosestChoice(RankedChoiceEvaluableForVector rcp, double x, double y, double z) {
		PackedCollection result = rcp.getClosestChoice(3).evaluate();
		Assert.assertEquals(3, result.getShape().getTotalSize());
		Assert.assertEquals(x, result.toDouble(0), 1e-5);
		Assert.assertEquals(y, result.toDouble(1), 1e-5);
		Assert.assertEquals(z, result.toDouble(2), 1e-5);
	}

	/**
	 * The device-side selection picks the candidate with the smallest rank, wherever it sits
	 * in the list.
	 */
	@Test(timeout = 60000)
	public void closestChoiceSelectsSmallestRank() {
		double[][] values = { { 1, 2, 3 }, { 4, 5, 6 }, { 7, 8, 9 } };
		assertClosestChoice(rankedChoice(values, 2.0, 1.0, 3.0), 4, 5, 6);
		assertClosestChoice(rankedChoice(values, 0.5, 1.0, 3.0), 1, 2, 3);
		assertClosestChoice(rankedChoice(values, 2.0, 3.0, 0.25), 7, 8, 9);
	}

	/**
	 * A candidate whose rank is below the epsilon threshold (a miss, or a hit behind the ray
	 * origin) is never chosen, even though its rank is the smallest.
	 */
	@Test(timeout = 60000)
	public void closestChoiceSkipsRanksBelowEpsilon() {
		double[][] values = { { 0.7034, 0.7034, 0.7034 }, { 9, 9, 9 }, { 8, 8, 8 } };
		assertClosestChoice(rankedChoice(values, 0.9002, -17.274, 0.0), 0.7034, 0.7034, 0.7034);
	}

	/** With no candidate at or above the epsilon threshold the blank (zero) value is chosen. */
	@Test(timeout = 60000)
	public void closestChoiceIsBlankWhenNothingIsHit() {
		double[][] values = { { 1, 2, 3 }, { 4, 5, 6 } };
		assertClosestChoice(rankedChoice(values, -1.0, 0.0), 0, 0, 0);
	}

	/** Equal ranks are resolved in favour of the earlier candidate, as by {@code evaluate}. */
	@Test(timeout = 60000)
	public void closestChoiceTieFavoursFirstCandidate() {
		double[][] values = { { 1, 2, 3 }, { 4, 5, 6 }, { 7, 8, 9 } };
		assertClosestChoice(rankedChoice(values, 3.0, 1.5, 1.5), 4, 5, 6);
	}

	/** A distant hit is still preferred to the blank value, and the nearer of two distant hits wins. */
	@Test(timeout = 60000)
	public void closestChoiceKeepsLargeRanks() {
		double[][] values = { { 1, 2, 3 }, { 4, 5, 6 } };
		assertClosestChoice(rankedChoice(values, -1.0, 1.0e6), 4, 5, 6);
		assertClosestChoice(rankedChoice(values, 2.0e6, 1.0e6), 4, 5, 6);
	}

	/** An empty candidate list yields the blank value of the requested length. */
	@Test(timeout = 60000)
	public void closestChoiceOfNoCandidatesIsBlank() {
		assertClosestChoice(new RankedChoiceEvaluableForVector(Intersection.e), 0, 0, 0);
	}

	/**
	 * The compiled form, {@link RankedChoiceEvaluableForVector#getAccelerated()}, makes the
	 * same selection and converts the output to a {@link Vector}.
	 */
	@Test(timeout = 60000)
	public void acceleratedClosestChoiceProducesVector() {
		double[][] values = { { 1, 2, 3 }, { 4, 5, 6 }, { 7, 8, 9 } };
		Vector result = rankedChoice(values, 2.0, 1.0, 3.0).getAccelerated().evaluate();
		Assert.assertEquals(4.0, result.getX(), 1e-5);
		Assert.assertEquals(5.0, result.getY(), 1e-5);
		Assert.assertEquals(6.0, result.getZ(), 1e-5);
	}
}
