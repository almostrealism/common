package org.almostrealism.util;

import org.almostrealism.algebra.Intersection;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.hardware.DynamicAcceleratedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryBankAdapter;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class RankedChoiceProducerTest {
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
		ProducerWithRank<Vector> v1 =
				new ProducerWithRankAdapter<>(StaticProducer.of(new Vector(1, 2, 3)),
						StaticProducer.of(2));
		ProducerWithRank<Vector> v2 =
				new ProducerWithRankAdapter<>(StaticProducer.of(new Vector(4, 5, 6)),
						StaticProducer.of(1));
		ProducerWithRank<Vector> v3 =
				new ProducerWithRankAdapter<>(StaticProducer.of(new Vector(7, 8, 9)),
						StaticProducer.of(3));

		RankedChoiceProducerForVector rcp = new RankedChoiceProducerForVector(Intersection.e);
		rcp.add(v1);
		rcp.add(v2);
		rcp.add(v3);
		return rcp;
	}

	protected RankedChoiceProducerForVector getRankedChoiceProducer2() {
		ProducerWithRank<Vector> v1 =
				new ProducerWithRankAdapter<>(StaticProducer.of(new Vector(0.7034, 0.7034, 0.7034)),
						StaticProducer.of(0.9002));
		ProducerWithRank<Vector> v2 =
				new ProducerWithRankAdapter<>(StaticProducer.of(new Vector(0.0, 0.0, 0.0)),
						StaticProducer.of(-17.274));

		RankedChoiceProducerForVector rcp = new RankedChoiceProducerForVector(Intersection.e);
		rcp.add(v1);
		rcp.add(v2);
		return rcp;
	}

	@Test
	public void rankedChoice1() {
		RankedChoiceProducerForVector rcp = getRankedChoiceProducer1();
		DynamicAcceleratedProducer<Vector> acc = rcp.getAccelerated();
		System.out.println(acc.getFunctionDefinition());

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}

	@Test
	public void rankedChoice2() {
		RankedChoiceProducerForVector rcp = getRankedChoiceProducer2();
		DynamicAcceleratedProducer<Vector> acc = rcp.getAccelerated();
		System.out.println(acc.getFunctionDefinition());

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(0.7034, 0.7034, 0.7034));
	}

	@Test
	public void rankedChoiceCompact1() {
		RankedChoiceProducerForVector rcp = getRankedChoiceProducer1();
		DynamicAcceleratedProducer<Vector> acc = rcp.getAccelerated();
		acc.compact();
		System.out.println(acc.getFunctionDefinition());

		Vector result = acc.evaluate();
		System.out.println("result = " + result);
		assert result.equals(new Vector(4, 5, 6));
	}

	@Test
	public void randomRankedChoiceKernel() {
		List<ProducerWithRank<Scalar>> values = Arrays.asList(
				new ProducerWithRankAdapter<>(PassThroughProducer.of(Scalar.class, 0),
										PassThroughProducer.of(Scalar.class, 1)),
				new ProducerWithRankAdapter<>(PassThroughProducer.of(Scalar.class, 2),
										PassThroughProducer.of(Scalar.class, 3)),
				new ProducerWithRankAdapter<>(PassThroughProducer.of(Scalar.class, 4),
										PassThroughProducer.of(Scalar.class, 5)));

		AcceleratedRankedChoiceProducer<Scalar> acc =
				new AcceleratedRankedChoiceProducer<>(2, Scalar.blank(), ScalarBank::new,
													values, Scalar.blank(), Intersection.e, Scalar.blank()::evaluate);

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
