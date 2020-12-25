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
import org.almostrealism.algebra.computations.ProducerWithRankAdapter;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.computations.AcceleratedRankedChoiceEvaluable;
import org.almostrealism.geometry.computations.RankedChoiceProducer;
import org.almostrealism.geometry.computations.RankedChoiceProducerForVector;
import org.almostrealism.hardware.DynamicAcceleratedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryBankAdapter;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class RankedChoiceProducerTest implements CodeFeatures {
	@Test
	public void highestRank() {
		Scalar in = new Scalar(1.0);
		Pair out = RankedChoiceProducer.highestRank.evaluate(
				new Object[] { in, new Pair(3, Intersection.e) });

		System.out.println("rank = " + out.getA());
		Assert.assertEquals(1.0, out.getA(), Math.pow(10, -10));
	}

	@Test
	public void highestRankKernel() {
		ScalarBank in = new ScalarBank(4, MemoryBankAdapter.CacheLevel.ACCESSED);
		in.set(0, new Scalar(0.0));
		in.set(1, new Scalar(2.0));
		in.set(2, new Scalar(1.0));
		in.set(3, new Scalar(3.0));

		PairBank out = new PairBank(1);

		PairBank conf = new PairBank(1);
		conf.set(0, new Pair(4, Intersection.e));

		RankedChoiceProducer.highestRank.kernelEvaluate(out, new MemoryBank[] { in, conf });

		System.out.println("rank = " + out.get(0).getA());
		Assert.assertEquals(1.0, out.get(0).getA(), Math.pow(10, -10));
	}

	protected RankedChoiceProducerForVector getRankedChoiceProducer1() {
		ProducerWithRank<Vector, Scalar> v1 =
				new ProducerWithRankAdapter<>(vector(1, 2, 3),
						scalar(2));
		ProducerWithRank<Vector, Scalar> v2 =
				new ProducerWithRankAdapter<>(vector(4, 5, 6),
						scalar(1));
		ProducerWithRank<Vector, Scalar> v3 =
				new ProducerWithRankAdapter<>(vector(7, 8, 9),
						scalar(3));

		RankedChoiceProducerForVector rcp = new RankedChoiceProducerForVector(Intersection.e);
		rcp.add(v1);
		rcp.add(v2);
		rcp.add(v3);
		return rcp;
	}

	protected RankedChoiceProducerForVector getRankedChoiceProducer2() {
		ProducerWithRank<Vector, Scalar> v1 =
				new ProducerWithRankAdapter<>(vector(0.7034, 0.7034, 0.7034),
						scalar(0.9002));
		ProducerWithRank<Vector, Scalar> v2 =
				new ProducerWithRankAdapter<>(vector(0.0, 0.0, 0.0),
						scalar(-17.274));

		RankedChoiceProducerForVector rcp = new RankedChoiceProducerForVector(Intersection.e);
		rcp.add(v1);
		rcp.add(v2);
		return rcp;
	}

	@Test
	public void rankedChoice1() {
		RankedChoiceProducerForVector rcp = getRankedChoiceProducer1();
		DynamicAcceleratedEvaluable<Vector, Vector> acc = rcp.getAccelerated();
		System.out.println(acc.getFunctionDefinition());

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}

	@Test
	public void rankedChoice2() {
		RankedChoiceProducerForVector rcp = getRankedChoiceProducer2();
		DynamicAcceleratedEvaluable<Vector, Vector> acc = rcp.getAccelerated();
		System.out.println(acc.getFunctionDefinition());

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(0.7034, 0.7034, 0.7034));
	}

	@Test
	public void rankedChoiceCompact1() {
		RankedChoiceProducerForVector rcp = getRankedChoiceProducer1();
		DynamicAcceleratedEvaluable<Vector, Vector> acc = rcp.getAccelerated();
		acc.compact();
		System.out.println(acc.getFunctionDefinition());

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}

	@Test
	public void randomRankedChoiceKernel() {
		List<ProducerWithRank<Scalar, Scalar>> values = Arrays.asList(
				new ProducerWithRankAdapter<>(PassThroughEvaluable.of(Scalar.class, 0),
										PassThroughEvaluable.of(Scalar.class, 1)),
				new ProducerWithRankAdapter<>(PassThroughEvaluable.of(Scalar.class, 2),
										PassThroughEvaluable.of(Scalar.class, 3)),
				new ProducerWithRankAdapter<>(PassThroughEvaluable.of(Scalar.class, 4),
										PassThroughEvaluable.of(Scalar.class, 5)));

		AcceleratedRankedChoiceEvaluable<Scalar> acc =
				new AcceleratedRankedChoiceEvaluable<>(2, Scalar::new, ScalarBank::new,
													values, Scalar.blank(), Intersection.e, Scalar.blank().get()::evaluate);

		System.out.println(acc.getFunctionDefinition());

		Scalar result = acc.evaluate(new Object[] { new Scalar(1), new Scalar(0), new Scalar(2),
												new Scalar(1), new Scalar(3), new Scalar(2) });
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
		acc.kernelEvaluate(output, input);

		Assert.assertEquals(0.0, output.get(0).getValue(), Math.pow(10, -10));

		count = 10;

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
		acc.kernelEvaluate(output, input);

		boolean failed = false;

		System.out.println("RankedChoiceProducerTest: Comparing...");
		for (int i = 0; i < output.getCount(); i++) {
			Scalar value = acc.evaluate(new Object[] { input[0].get(i), input[1].get(i), input[2].get(i),
													input[3].get(i), input[4].get(i), input[5].get(i) });
			if (Math.abs(value.getValue() - output.get(i).getValue()) > Math.pow(10, -10)) {
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
