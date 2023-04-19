package org.almostrealism.algebra.computations.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducerBase;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class DitherTest implements TestFeatures {
	@Test
	public void dither() {
		ScalarBankProducerBase dither = dither(200, v(400, 0), v(Scalar.shape(), 1));
		ScalarBank result = dither.get().evaluate(new ScalarBank(200), new Scalar(1.0));
		assertNotEquals(0.0, result.get(20));
	}

	@Test
	public void random() {
		ScalarBank random = new ScalarBank(160);
		IntStream.range(0, 160).forEach(i ->  random.set(i, 100 * Math.random()));
		ScalarBankProducerBase dither = dither(160, v(320, 0), v(Scalar.shape(), 1));
		ScalarBank out = dither.get().evaluate(random, new Scalar(1.0));
		System.out.println(Arrays.toString(IntStream.range(0, 160).mapToDouble(i -> out.get(i).getValue()).toArray()));
		assertNotEquals(0.0, out.get(20));
	}
}
