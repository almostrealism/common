package org.almostrealism.util;

import org.almostrealism.algebra.Intersection;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.geometry.RayBank;
import org.almostrealism.graph.mesh.Triangle;
import org.almostrealism.math.MemoryBank;
import org.almostrealism.math.MemoryBankAdapter;
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
}
