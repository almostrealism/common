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
}
