/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ProducerWithRank;
import org.almostrealism.algebra.computations.ProducerWithRankAdapter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.computations.RankedChoiceEvaluable;
import org.almostrealism.geometry.computations.RankedChoiceEvaluableForVector;
import org.almostrealism.hardware.Hardware;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class RankedChoiceEvaluableTest implements TestFeatures {
	private double gap = 10 * Hardware.getLocalHardware().getPrecision().epsilon(true);

	@Test
	public void highestRank() {
		if (skipKnownIssues) return;

		IntStream.range(0, 5).forEach(i -> {
			Scalar in = new Scalar(1.0);
			Pair out = RankedChoiceEvaluable.highestRank.evaluate(
					in, new Pair(3, Intersection.e));

			System.out.println("rank = " + out.getA());
			assertEquals(1.0, out.getA());
		});
	}

	@Test
	public void highestRankKernel() {
		PackedCollection<Scalar> in = Scalar.scalarBank(4);
		in.set(0, new Scalar(0.0));
		in.set(1, new Scalar(2.0));
		in.set(2, new Scalar(1.0));
		in.set(3, new Scalar(3.0));

		PackedCollection<Pair<?>> out = Pair.bank(1);

		PackedCollection<Pair<?>> conf = Pair.bank(1);
		conf.set(0, new Pair(4, Intersection.e));

		RankedChoiceEvaluable.highestRank.into(out).evaluate(in, conf);

		out.get(0).print();
		Assert.assertEquals(1.0, out.get(0).getA(), Math.pow(10, -10));
	}

	protected RankedChoiceEvaluableForVector getRankedChoiceProducer1() {
		ProducerWithRank<Vector, Scalar> v1 =
				new ProducerWithRankAdapter<>(vector(1, 2, 3),
						scalar(2));
		ProducerWithRank<Vector, Scalar> v2 =
				new ProducerWithRankAdapter<>(vector(4, 5, 6),
						scalar(1));
		ProducerWithRank<Vector, Scalar> v3 =
				new ProducerWithRankAdapter<>(vector(7, 8, 9),
						scalar(3));

		RankedChoiceEvaluableForVector rcp = new RankedChoiceEvaluableForVector(Intersection.e);
		rcp.add(v1);
		rcp.add(v2);
		rcp.add(v3);
		return rcp;
	}

	protected RankedChoiceEvaluableForVector getRankedChoiceProducer2() {
		ProducerWithRank<Vector, Scalar> v1 =
				new ProducerWithRankAdapter<>(vector(0.7034, 0.7034, 0.7034),
						scalar(0.9002));
		ProducerWithRank<Vector, Scalar> v2 =
				new ProducerWithRankAdapter<>(vector(0.0, 0.0, 0.0),
						scalar(-17.274));

		RankedChoiceEvaluableForVector rcp = new RankedChoiceEvaluableForVector(Intersection.e);
		rcp.add(v1);
		rcp.add(v2);
		return rcp;
	}

	// TODO  @Test
	public void rankedChoice1() {
		RankedChoiceEvaluableForVector rcp = getRankedChoiceProducer1();
		Evaluable<Vector> acc = rcp.getAccelerated();

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}

	// TODO  @Test
	public void rankedChoice2() {
		RankedChoiceEvaluableForVector rcp = getRankedChoiceProducer2();
		Evaluable<Vector> acc = rcp.getAccelerated();

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(0.7034, 0.7034, 0.7034));
	}

	// TODO  @Test
	public void rankedChoiceCompact1() {
		RankedChoiceEvaluableForVector rcp = getRankedChoiceProducer1();
		Evaluable<Vector> acc = rcp.getAccelerated();

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}
}
