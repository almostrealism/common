package org.almostrealism.collect.computations.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.metal.MetalOperator;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSettings;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class PackedCollectionRepeatTests implements TestFeatures {
	static {
		NativeCompiler.enableInstructionSetMonitoring = !TestSettings.skipLongTests;
		MetalProgram.enableProgramMonitoring = !TestSettings.skipLongTests;
	}

	@Test
	public void repeatItem() {
		int w = 2;
		int h = 3;
		int d = 4;

		PackedCollection<?> v = new PackedCollection<>(shape(w, h));
		v.fill(pos -> Math.random());

		PackedCollection<?> out = cp(v).traverse().repeat(d).get().evaluate();

		print(2, 12, out);

		for (int x = 0; x < w; x++) {
			for (int y = 0; y < d; y++) {
				for (int z = 0; z < h; z++) {
					double expected = v.valueAt(x, z);
					double actual = out.valueAt(x, y, z);
					System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
					assertEquals(expected, actual);
				}
			}
		}
	}

	@Test
	public void repeat3d() {
		int w = 1;
		int h = 2;
		int d = 4;

		PackedCollection<?> v = new PackedCollection<>(shape(w, h));
		v.fill(pos -> Math.random());

		MetalOperator.verboseLog(() -> {
			PackedCollection<?> out = c(p(v)).traverseEach().expand(d, x -> x.repeat(d)).get().evaluate();

			for (int x = 0; x < w; x++) {
				for (int y = 0; y < h; y++) {
					for (int z = 0; z < d; z++) {
						double expected = v.valueAt(x, y);
						double actual = out.valueAt(x, y, z);
						System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
						assertEquals(expected, actual);
					}
				}
			}
		});
	}

	@Test
	public void repeatSum() {
		int size = 30;
		int nodes = 10;

		Tensor<Double> t = tensor(shape(size));
		PackedCollection<?> input = t.pack();

		PackedCollection<?> weights = new PackedCollection<>(shape(size, nodes));
		weights.fill(pos -> Math.random());

		Supplier<Producer<PackedCollection<?>>> dense =
				() -> cp(input).repeat(nodes).each().traverse(1).sum();

		Consumer<PackedCollection<?>> valid = output -> {
			for (int i = 0; i < nodes; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					expected += input.valueAt(x);
				}

				double actual = output.valueAt(i);

				System.out.println("PackedCollectionSubsetTests: [" + i + "] " + expected + " vs " + actual);
				Assert.assertEquals(expected, actual, 0.0001);
			}
		};

		kernelTest(dense, valid);
	}

	@Test
	public void repeatEnumerateMultiply() {
		int size = 30;
		int nodes = 10;

		Tensor<Double> t = tensor(shape(size));
		PackedCollection<?> input = t.pack();

		PackedCollection<?> weights = new PackedCollection<>(shape(size, nodes));
		weights.fill(pos -> Math.random());

		Supplier<Producer<PackedCollection<?>>> dense =
				() -> c(p(input)).repeat(nodes).traverseEach()
						.multiply(c(p(weights))
								.enumerate(1, 1))
						.traverse(1).sum();

		Consumer<PackedCollection<?>> valid = output -> {
			for (int i = 0; i < nodes; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					expected += weights.valueAt(x, i) * input.valueAt(x);
				}

				double actual = output.valueAt(i);

				System.out.println("PackedCollectionSubsetTests: [" + i + "] " + expected + " vs " + actual);
				Assert.assertEquals(expected, actual, 0.0001);
			}
		};

		kernelTest(dense, valid);
	}

	@Test
	public void repeatEnumerateMultiplyAdd() {
		int size = 30;
		int nodes = 10;

		Tensor<Double> t = tensor(shape(size));
		PackedCollection<?> input = t.pack();

		PackedCollection<?> weights = new PackedCollection<>(shape(size, nodes));
		weights.fill(pos -> Math.random());

		PackedCollection<?> biases = new PackedCollection<>(shape(nodes));
		biases.fill(pos -> Math.random());

		Supplier<Producer<PackedCollection<?>>> dense =
				() -> c(p(input)).repeat(nodes).traverseEach()
						.multiply(c(p(weights))
								.enumerate(1, 1))
						.traverse(1).sum()
						.add(p(biases));

		Consumer<PackedCollection<?>> valid = output -> {
			for (int i = 0; i < nodes; i++) {
				double expected = 0;

				for (int x = 0; x < size; x++) {
					expected += weights.valueAt(x, i) * input.valueAt(x);
				}

				double actual = output.valueAt(i);
				Assert.assertNotEquals(expected, actual, 0.0001);

				expected += biases.valueAt(i);
				System.out.println("PackedCollectionSubsetTests: [" + i + "] " + expected + " vs " + actual);
				Assert.assertEquals(expected, actual, 0.0001);
			}
		};

		kernelTest(dense, valid);
	}

	@Test
	public void maxRepeat() {
		PackedCollection<?> in = new PackedCollection<>(8, 4).randFill();
		in.traverse(1).print();
		System.out.println("--");

		PackedCollection<?> o = cp(in).traverse(1).max().repeat(3).get().evaluate();
		o.traverse(1).print();

		for (int h = 0; h < 8; h++) {
			double max = in.valueAt(h, 0);
			for (int i = 1; i < 4; i++) {
				if (in.valueAt(h, i) > max) {
					max = in.valueAt(h, i);
				}
			}

			for (int i = 0; i < 3; i++) {
				double actual = o.valueAt(h, i, 0);
				System.out.println("CollectionRepeatTests[" + h + "] " + max + " vs " + actual);
				assertEquals(max, actual);
			}
		}
	}
}
