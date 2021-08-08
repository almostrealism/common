package org.almostrealism.algebra.computations.jni.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.jni.NativeDither160;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class NativeDitherTest implements TestFeatures {
	@Test
	public void random() {
		ScalarBank random = new ScalarBank(160);
		IntStream.range(0, 160).forEach(i ->  random.set(i, 100 * Math.random()));
		NativeDither160 dither = new NativeDither160();
		ScalarBank out = dither.get().evaluate(random, new Scalar(1.0));
		assertNotEquals(0.0, out.get(20));
	}
}
