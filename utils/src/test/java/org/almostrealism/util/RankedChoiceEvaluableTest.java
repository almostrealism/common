/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.relation.ProducerWithRank;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.computations.ProducerWithRankAdapter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.computations.AcceleratedRankedChoiceEvaluable;
import org.almostrealism.geometry.computations.RankedChoiceEvaluable;
import org.almostrealism.geometry.computations.RankedChoiceEvaluableForVector;
import org.almostrealism.hardware.DynamicAcceleratedEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryBank;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class RankedChoiceEvaluableTest implements CodeFeatures {
	private double gap = Hardware.getLocalHardware().isDoublePrecision() ? Math.pow(10, -10) : Math.pow(10, -6);

	@Test
	public void highestRank() {
		IntStream.range(0, 5).forEach(i -> {
			Scalar in = new Scalar(1.0);
			Pair out = RankedChoiceEvaluable.highestRank.evaluate(
					new Object[]{in, new Pair(3, Intersection.e)});

			System.out.println("rank = " + out.getA());
			Assert.assertEquals(1.0, out.getA(), Math.pow(10, -10));
		});
	}

	@Test
	public void highestRankKernel() {
		ScalarBank in = new ScalarBank(4);
		in.set(0, new Scalar(0.0));
		in.set(1, new Scalar(2.0));
		in.set(2, new Scalar(1.0));
		in.set(3, new Scalar(3.0));

		PackedCollection<Pair<?>> out = Pair.bank(1);

		PackedCollection<Pair<?>> conf = Pair.bank(1);
		conf.set(0, new Pair(4, Intersection.e));

		RankedChoiceEvaluable.highestRank.into(out).evaluate(in, conf);

		System.out.println("rank = " + out.get(0).getA());
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
		DynamicAcceleratedEvaluable<Vector, Vector> acc = rcp.getAccelerated();

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}

	// TODO  @Test
	public void rankedChoice2() {
		RankedChoiceEvaluableForVector rcp = getRankedChoiceProducer2();
		DynamicAcceleratedEvaluable<Vector, Vector> acc = rcp.getAccelerated();

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(0.7034, 0.7034, 0.7034));
	}

	// TODO  @Test
	public void rankedChoiceCompact1() {
		RankedChoiceEvaluableForVector rcp = getRankedChoiceProducer1();
		DynamicAcceleratedEvaluable<Vector, Vector> acc = rcp.getAccelerated();
		acc.compile();

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}

	// TODO  @Test
	public void randomRankedChoiceKernel() {
		List<ProducerWithRank<Scalar, Scalar>> values = Arrays.asList(
				new ProducerWithRankAdapter<>(v(Scalar.shape(), 0),
										v(Scalar.shape(), 1)),
				new ProducerWithRankAdapter<>(v(Scalar.shape(), 2),
										v(Scalar.shape(), 3)),
				new ProducerWithRankAdapter<>(v(Scalar.shape(), 4),
										v(Scalar.shape(), 5)));

		AcceleratedRankedChoiceEvaluable<Scalar> acc =
				new AcceleratedRankedChoiceEvaluable<>(2, Scalar::new, ScalarBank::new,
													values, Scalar.blank(), Intersection.e, Scalar.blank().get()::evaluate);

		Scalar result = acc.evaluate(new Scalar(1), new Scalar(0), new Scalar(2),
				new Scalar(1), new Scalar(3), new Scalar(2));
		Assert.assertEquals(2, result.getValue(), Math.pow(10, -10));

		int count = 1;

		System.out.println("RankedChoiceProducerTest: Preparing random input...");

		ScalarBank input[] = new ScalarBank[] { new ScalarBank(count), new ScalarBank(count), new ScalarBank(count),
				new ScalarBank(count), new ScalarBank(count), new ScalarBank(count) };
		input[0].set(0, 0.13229523881923733, 1.0);
		input[1].set(0, -0.9907866131625955, 1.0);
		input[2].set(0, -0.9494781072737721, 1.0);
		input[3].set(0, -0.20104796782364365, 1.0);
		input[4].set(0, -0.4483061040652183, 1.0);
		input[5].set(0, -0.4508810286585523, 1.0);

		ScalarBank output = new ScalarBank(count);
		acc.into(output).evaluate(input);

		Assert.assertEquals(0.0, output.get(0).getValue(), Math.pow(10, -10));

		count = 1000;

		System.out.println("RankedChoiceProducerTest: Preparing random input...");

		input = new ScalarBank[] { new ScalarBank(count), new ScalarBank(count), new ScalarBank(count),
								new ScalarBank(count), new ScalarBank(count), new ScalarBank(count) };

		for (int i = 0; i < input.length; i++) {
			for (int j = 0; j < input[i].getCount(); j++) {
				input[i].set(j, 2 * Math.random() - 1, 1);
			}
		}

		System.out.println("RankedChoiceProducerTest: Evaluating kernel...");
		output = new ScalarBank(count);
		acc.into(output).evaluate(input);

		boolean failed = false;

		System.out.println("RankedChoiceProducerTest: Comparing...");
		for (int i = 0; i < output.getCount(); i++) {
			Scalar value = acc.evaluate(new Object[] { input[0].get(i), input[1].get(i), input[2].get(i),
													input[3].get(i), input[4].get(i), input[5].get(i) });
			if (Math.abs(value.getValue() - output.get(i).getValue()) > gap) {
				System.out.println(i + ": [" + input[0].get(i).getValue() + ", " + input[1].get(i).getValue() + "]" +
									"[" + input[2].get(i).getValue() + ", " + input[3].get(i).getValue() + "]" +
									"[" + input[4].get(i).getValue() + ", " + input[5].get(i).getValue() + "] = " +
									output.get(i).getValue() + " (expected " + value.getValue() + ")");
				failed = true;
			}
		}

		if (failed) Assert.fail();
	}
}
