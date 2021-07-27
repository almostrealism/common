package org.almostrealism.algebra.computations.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.Dither;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class DitherTest implements TestFeatures {
	@Test
	public void dither() {
		Dither dither = new Dither(200, v(400, 0), v(Scalar.class, 1));
		ScalarBank result = dither.get().evaluate(new ScalarBank(200), new Scalar(1.0));
		assertNotEquals(0.0, result.get(20));
	}
}
