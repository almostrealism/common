package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class CollectionMathTests implements TestFeatures {
	@Test
	public void sum() {
		int size = 768;

		PackedCollection<?> x = new PackedCollection<>(shape(size));
		x.fill(pos -> Math.random());


		kernelTest(() -> c(p(x)).sum(),
				output -> {
					double expected = 0;

					for (int j = 0; j < size; j++) {
						expected += x.valueAt(j);
					}

					Assert.assertEquals(expected, output.valueAt(0), 1e-5);
				}, false, false, true);
	}

	@Test
	public void squares() {
		int size = 768;

		PackedCollection<?> o = new PackedCollection<>(shape(size));
		o.fill(pos -> Math.random());

		PackedCollection<?> x = new PackedCollection<>(shape(size));
		x.fill(pos -> Math.random());

		PackedCollection<?> weight = new PackedCollection<>(shape(size));
		weight.fill(pos -> Math.random());


		kernelTest(() -> {
					CollectionProducer<PackedCollection<?>> ss = pow(traverseEach(p(x)), c(2.0));
					return ss;
				},
				output -> {
					for (int j = 0; j < size; j++) {
						Assert.assertEquals(output.valueAt(j), x.valueAt(j) * x.valueAt(j), 1e-5);
					}
				}, true, true, false);
	}

	@Test
	public void sumOfSquares() {
		int size = 768;

		PackedCollection<?> o = new PackedCollection<>(shape(size));
		o.fill(pos -> Math.random());

		PackedCollection<?> x = new PackedCollection<>(shape(size));
		x.fill(pos -> Math.random());

		PackedCollection<?> weight = new PackedCollection<>(shape(size));
		weight.fill(pos -> Math.random());


		kernelTest(() -> {
					CollectionProducer<PackedCollection<?>> ss = pow(p(x), c(2.0)).traverse(0).sum();
					ss = ss.divide(c(size)).add(c(1e-5));
					ss = c(1.0).divide(ss.pow(c(0.5)));
					return ss;
				},
				output -> {
					double ss = 0.0f;
					for (int j = 0; j < size; j++) {
						ss += x.valueAt(j) * x.valueAt(j);
					}
					ss /= size;
					ss += 1e-5f;
					ss = 1.0f / (float) Math.sqrt(ss);

					Assert.assertEquals(ss, output.valueAt(0), 1e-5);
				}, false, false, true);
	}

	@Test
	public void sumOfSquaresProduct() {
		int size = 768;

		PackedCollection<?> o = new PackedCollection<>(shape(size));
		o.fill(pos -> Math.random());

		PackedCollection<?> x = new PackedCollection<>(shape(size));
		x.fill(pos -> Math.random());

		PackedCollection<?> weight = new PackedCollection<>(shape(size));
		weight.fill(pos -> Math.random());


		kernelTest(() -> {
					CollectionProducer<PackedCollection<?>> ss = pow(p(x), c(2.0)).traverse(0).sum();
					ss = ss.divide(c(size)).add(c(1e-5));
					ss = c(1.0).divide(ss.pow(c(0.5)));
					return multiply(p(weight), p(x)).multiply(ss);
				},
				output -> {
					double ss = 0.0f;
					for (int j = 0; j < size; j++) {
						ss += x.valueAt(j) * x.valueAt(j);
					}
					ss /= size;
					ss += 1e-5f;
					ss = 1.0f / (float) Math.sqrt(ss);
					// normalize and scale
					for (int j = 0; j < size; j++) {
						Assert.assertEquals(weight.valueAt(j) * (ss * x.valueAt(j)), o.valueAt(j), 1e-5);
					}
				}, false, false, true);
	}
}
