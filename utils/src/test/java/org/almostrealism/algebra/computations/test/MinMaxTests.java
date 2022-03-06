package org.almostrealism.algebra.computations.test;

import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class MinMaxTests implements TestFeatures {
	@Test
	public void min() {
		assertEquals(1.0, min(v(1.0), v(6.0)).get().evaluate());
		assertEquals(1.0, min(v(6.0), v(1.0)).get().evaluate());
		assertEquals(0.5, min(v(0.5), v(0.7)).get().evaluate());
	}

	@Test
	public void max() {
		assertEquals(6.0, max(v(1.0), v(6.0)).get().evaluate());
		assertEquals(6.0, max(v(6.0), v(1.0)).get().evaluate());
		assertEquals(0.7, max(v(0.5), v(0.7)).get().evaluate());
	}
}
