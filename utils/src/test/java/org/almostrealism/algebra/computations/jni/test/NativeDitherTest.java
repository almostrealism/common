package org.almostrealism.algebra.computations.jni.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.Dither;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class NativeDitherTest implements TestFeatures {
	@Test
	public void random() {
		ScalarBank random = new ScalarBank(160);
		IntStream.range(0, 160).forEach(i ->  random.set(i, 100 * Math.random()));
		Dither dither = new Dither(160, v(ScalarBank.class, 0), v(Scalar.class, 1));
		ScalarBank out = dither.get().evaluate(random, new Scalar(1.0));
		assertNotEquals(0.0, out.get(20));
	}
}
