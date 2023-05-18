package org.almostrealism.geometry.test;

import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class BasicGeometryKernelTest implements TestFeatures {
	// TODO  @Test
	public void test() {
		ScalarBank b = integers(0, 10)
				.map(shape(1), v -> scalar(v))
				.scalarMap(v -> scalarsMultiply(v, v(10)))
				.collect(dims -> new ScalarBank(dims.length(1)));
		// TODO  Create translation matrix
		assertEquals(20, b.get(2));
	}
}
