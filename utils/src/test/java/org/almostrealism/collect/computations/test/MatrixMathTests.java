package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class MatrixMathTests implements TestFeatures {
	@Test
	public void matrixMultiply() {
		int size = 48;
		int n = size;
		int d = size;

		PackedCollection<?> x = new PackedCollection<>(shape(n));
		x.fill(pos -> Math.random());

		PackedCollection<?> weight = new PackedCollection<>(shape(d, n));
		weight.fill(pos -> Math.random());

		kernelTest(() -> reduce(traverse(1, p(weight)),
							v -> v.multiply(p(x)).sum()),
				output -> {
					for (int i = 0; i < d; i++) {
						double v = 0.0;

						for (int j = 0; j < n; j++) {
							v += weight.valueAt(i, j) * x.valueAt(j);
						}

						Assert.assertEquals(output.valueAt(i, 0), v, 1e-5);
					}
				}, false, false, true);
	}
}
