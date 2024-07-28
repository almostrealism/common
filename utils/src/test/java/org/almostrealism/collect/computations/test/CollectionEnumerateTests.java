package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.cl.CLOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class CollectionEnumerateTests implements TestFeatures {

	@Test
	public void enumerateSmall() {
		PackedCollection<?> input = integers(1, 17).evaluate().reshape(4, 4);
		input.traverse().print();
		System.out.println("--");

		CollectionProducer<PackedCollection<?>> producer = cp(input)
				.enumerate(1, 1)
				.enumerate(1, 2);
		PackedCollection<?> out = producer.get().evaluate();
		out = out.reshape(8, 2).traverse();
		out.print();

		assertEquals(1.0, out.valueAt(0, 0));
		assertEquals(5.0, out.valueAt(0, 1));
		assertEquals(2.0, out.valueAt(1, 0));
		assertEquals(6.0, out.valueAt(1, 1));
		assertEquals(3.0, out.valueAt(2, 0));
		assertEquals(7.0, out.valueAt(2, 1));
		assertEquals(4.0, out.valueAt(3, 0));
		assertEquals(8.0, out.valueAt(3, 1));
		assertEquals(9.0, out.valueAt(4, 0));
		assertEquals(13.0, out.valueAt(4, 1));
		assertEquals(10.0, out.valueAt(5, 0));
		assertEquals(14.0, out.valueAt(5, 1));
		assertEquals(11.0, out.valueAt(6, 0));
		assertEquals(15.0, out.valueAt(6, 1));
		assertEquals(12.0, out.valueAt(7, 0));
		assertEquals(16.0, out.valueAt(7, 1));
	}

	@Test
	public void enumerate2d() {
		Tensor<Double> t = tensor(shape(10, 10), (int[] c) -> c[1] < 2);
		PackedCollection<?> input = t.pack();

		CollectionProducer<PackedCollection<?>> producer = enumerate(shape(10, 2), traverseEach(p(input)));
		Evaluable<PackedCollection<?>> ev = producer.get();
		PackedCollection<?> enumerated = ev.evaluate().reshape(shape(5, 10, 2));

		Assert.assertEquals(5, enumerated.getShape().length(0));
		Assert.assertEquals(10, enumerated.getShape().length(1));
		Assert.assertEquals(2, enumerated.getShape().length(2));

		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < 10; j++) {
				for (int k = 0; k < 2; k++) {
					if (i == 0) {
						Assert.assertTrue(enumerated.toDouble(enumerated.getShape().index(i, j, k)) >= 0);
					} else {
						Assert.assertTrue(enumerated.toDouble(enumerated.getShape().index(i, j, k)) <= 0);
					}
				}
			}
		}
	}

	@Test
	public void slices() {
		int size = 4;
		int count = 3;

		PackedCollection<?> input = tensor(shape(size, size, count)).pack();

		CollectionProducer<PackedCollection<?>> enumerated = enumerate(shape(size, size, 1), p(input));
		PackedCollection<?> output = enumerated.get().evaluate();
		System.out.println(output.getShape());

		Assert.assertEquals(count, output.getShape().length(0));
		Assert.assertEquals(size, output.getShape().length(1));
		Assert.assertEquals(size, output.getShape().length(2));
		Assert.assertEquals(1, output.getShape().length(3));

		for (int i = 0; i < count; i++) {
			for (int x = 0; x < size; x++) {
				for (int y = 0; y < size; y++) {
					double expected = input.valueAt(x, y, i);
					System.out.println("PackedCollectionMapTests: " + expected + " vs " + output.valueAt(i, x, y, 0));
					Assert.assertEquals(expected, output.valueAt(i, x, y, 0), 0.0001);
				}
			}
		}
	}

	@Test
	public void dynamicSum() {
		if (skipKnownIssues) return;

		int r = 4;
		int c = 2;
		int count = 3;

		PackedCollection<?> input = new PackedCollection<>(shape(count, r, c, 1)).randFill();
		PackedCollection<?> output = new PackedCollection<>(shape(count, r, 1));

		Evaluable<PackedCollection<?>> sum = cv(shape(r, c, 1), 0).traverse(1).sum().get();
//		PackedCollection<?> output = sum.evaluate(input.traverse(1));
		sum.into(output.traverse(2)).evaluate(input.traverse(1));

		Assert.assertEquals(count, output.getShape().length(0));
		Assert.assertEquals(r, output.getShape().length(1));
		Assert.assertEquals(1, output.getShape().length(2));

		for (int i = 0; i < count; i++) {
			for (int j = 0; j < r; j++) {
				double total = 0.0;
				for (int k = 0; k < c; k++) {
					total += input.valueAt(i, j, k, 0);
				}

				assertEquals(total, output.valueAt(i, j, 0));
			}
		}
	}

	@Test
	public void enumerateProduct() {
		PackedCollection<?> input = tensor(shape(6, 4)).pack();
		PackedCollection<?> operand = tensor(shape(4, 6, 1)).pack();

		CollectionProducer<PackedCollection<?>> product = enumerate(shape(6, 1), p(input)).traverse(0).multiply(p(operand));
		System.out.println(product.getShape());

		Evaluable<PackedCollection<?>> ev = product.get();
		PackedCollection<?> enumerated = ev.evaluate().reshape(shape(4, 6));

		Assert.assertEquals(4, enumerated.getShape().length(0));
		Assert.assertEquals(6, enumerated.getShape().length(1));

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 6; j++) {
				Assert.assertEquals(input.toDouble(input.getShape().index(j, i)) * operand.toDouble(operand.getShape().index(i, j)),
						enumerated.toDouble(enumerated.getShape().index(i, j)), 0.0001);
			}
		}
	}

	@Test
	public void enumerateDivide() {
		int w = 4; // 1024;
		int h = 9;
		int d = 145;

		TraversalPolicy inShape = shape(w, h, d);
		TraversalPolicy outputShape = shape(h, w);

		PackedCollection<?> in = new PackedCollection<>(inShape);

		in.fill(pos -> Math.random());

		CollectionProducer<PackedCollection<?>> o =
				c(p(in))
						.traverse(2).sum()
						.divide(c(Math.sqrt(d)))
						.reshape(shape(w, h))
						.enumerate(1, 1)
						.reshape(outputShape);

		verboseLog(() -> {
//			PackedCollection<?> out = o.get().evaluate();
			// TODO This should not require optimization to pass, but currently it does
			PackedCollection<?> out = ((Evaluable<PackedCollection<?>>) ((ParallelProcess) o).optimize().get()).evaluate();

			for (int n = 0; n < h; n++) {
				for (int t = 0; t < w; t++) {
					double total = 0.0;

					for (int i = 0; i < d; i++) {
						total += in.valueAt(t, n, i);
					}

					total /= Math.sqrt(d);

					System.out.println("PackedCollectionSubsetTests[" + n + ", " + t + "]: " + total + " vs " + out.valueAt(n, t));
					assertEquals(total, out.valueAt(n, t));
				}
			}
		});
	}

	@Test
	public void enumerate2dProduct() {
		Tensor<Double> t = tensor(shape(4, 6));
		PackedCollection<?> input = t.pack().traverseEach();

		PackedCollection<?> operand = new PackedCollection<>(shape(6, 4));
		operand.fill(pos -> Math.random());
		operand = operand.traverseEach();

		Producer<PackedCollection<?>> product = enumerate(shape(4, 1), p(input)).traverse(0)
				.multiply(enumerate(shape(1, 4), p(operand)).traverse(0));

		Evaluable<PackedCollection<?>> ev = product.get();
		PackedCollection<?> enumerated = ev.evaluate().reshape(shape(6, 4));

		Assert.assertEquals(6, enumerated.getShape().length(0));
		Assert.assertEquals(4, enumerated.getShape().length(1));

		for (int i = 0; i < 6; i++) {
			for (int j = 0; j < 4; j++) {
				Assert.assertEquals(input.toDouble(input.getShape().index(j, i)) * operand.toDouble(operand.getShape().index(i, j)),
						enumerated.toDouble(enumerated.getShape().index(i, j)), 0.0001);
			}
		}
	}

	@Test
	public void stride2dProduct() {
		Tensor<Double> t = tensor(shape(8, 10));
		PackedCollection<?> input = t.pack();

		PackedCollection<?> filter = tensor(shape(9, 8, 2), (int[] c) -> {
			int i = c[0], j = c[1], k = c[2];
			return i == 0 || i == 8 || j == 0 || j == 7 || k == 0 || k == 1;
		}).pack();

		Producer<PackedCollection<?>> stride =
				enumerate(1, 2, 1, p(input))
						.traverse(0)
						.multiply(p(filter));
		Evaluable<PackedCollection<?>> ev = stride.get();
		PackedCollection<?> enumerated = ev.evaluate().reshape(shape(9, 8, 2));

		System.out.println(enumerated.getShape());

		for (int i = 0; i < 9; i++) {
			for (int j = 0; j < 8; j++) {
				for (int k = 0; k < 2; k++) {
					Assert.assertEquals(input.toDouble(input.getShape().index(j, i + k)) *
									filter.toDouble(filter.getShape().index(i, j, k)),
							enumerated.toDouble(enumerated.getShape().index(i, j, k)), 0.0001);
				}
			}
		}
	}

	@Test
	public void enumerateTwiceSmall() {
		PackedCollection<?> input = tensor(shape(4, 4)).pack();

//		verboseLog(() -> {
		CollectionProducer<PackedCollection<?>> convY = c(p(input))
				.enumerate(1, 2, 2);
		PackedCollection<?> output = convY.get().evaluate();
		System.out.println(output.getShape());

		CollectionProducer<PackedCollection<?>> convX = c(traverse(0, p(output)))
				.enumerate(1, 2, 2);
		output = convX.get().evaluate();
		System.out.println(output.getShape());

		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2; j++) {
				for (int k = 0; k < 2; k++) {
					for (int l = 0; l < 2; l++) {
						double expected = input.toDouble(input.getShape().index(i * 2 + k, j * 2 + l));
						double actual = output.toDouble(output.getShape().index(i, j, k, l));
						System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		}
//		});
	}

	@Test
	public void doubleEnumerateSmall() {
		int r = 4;
		int c = 4;
		int w = 2;
		int s = 2;

		PackedCollection<?> input = tensor(shape(r, c)).pack();

		CollectionProducer<PackedCollection<?>> conv = c(p(input))
				.enumerate(1, w, s)
				.enumerate(1, w, s);
		PackedCollection<?> output = conv.get().evaluate();
		System.out.println(output.getShape());

		for (int i = 0; i < r; i += s) {
			for (int j = 0; j < c; j += s) {
				System.out.println("i: " + i + " j: " + j);
				for (int k = 0; k < w; k++) {
					for (int l = 0; l < w; l++) {
						double expected = input.toDouble(input.getShape().index(i + k, j + l));
						double actual = output.toDouble(output.getShape().index(i / s, j / s, k, l));
						System.out.println("PackedCollectionSubsetTests: " + expected + " vs " + actual);
						Assert.assertEquals(expected, actual, 0.0001);
					}
				}
			}
		}
	}

	@Test
	public void doubleEnumerate4d() {
		int n = 1; int c = 4;
		int w = 6; int h = 6;
		int x = 3; int s = 1;

		PackedCollection<?> input =
				new PackedCollection<>(shape(n, c, h, w))
					.fill(Math::random);

		CollectionProducer<PackedCollection<?>> conv =
				c(p(input))
					.enumerate(3, x, s)
					.enumerate(3, x, s);
		PackedCollection<?> output = conv.get().evaluate();
		System.out.println(output.getShape());

		for (int np = 0; np < n; np++) {
			for (int cp = 0; cp < c; cp++) {
				for (int i = 0; i < w; i += s) {
					for (int j = 0; j < h; j += s) {
						log("i: " + i + " j: " + j);

						for (int k = 0; k < x; k++) {
							for (int l = 0; l < x; l++) {
								double expected = input.valueAt(np, cp, i + k, j + l);
								double actual = output.valueAt(np, cp, i / s, j / s, k, l);
								log(expected + " vs " + actual);
								assertEquals(expected, actual);
							}
						}
					}
				}
			}
		}
	}

	@Test
	public void enumerateTwice() {
		PackedCollection<?> input = tensor(shape(10, 10)).pack();

		verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> convY = c(p(input))
					.enumerate(1, 3, 1);
			PackedCollection<?> output = convY.get().evaluate();
			System.out.println(output.getShape());

			CollectionProducer<PackedCollection<?>> convX = c(traverse(0, p(output)))
					.enumerate(1, 3, 1);
			output = convX.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < 8; i++) {
				for (int j = 0; j < 8; j++) {
					for (int k = 0; k < 3; k++) {
						for (int l = 0; l < 3; l++) {
							double expected = input.toDouble(input.getShape().index(i + k, j + l));
							double actual = output.toDouble(output.getShape().index(i, j, k, l));
							System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
							Assert.assertEquals(expected, actual, 0.0001);
						}
					}
				}
			}
		});
	}

	// @Test
	public void doubleStride2dProduct() {
		Tensor<Double> t = tensor(shape(8, 10));
		PackedCollection<?> input = t.pack();

		PackedCollection<?> filter = tensor(shape(9, 8, 2), (int[] c) -> {
			int i = c[0], j = c[1], k = c[2];
			return i == 0 || i == 8 || j == 0 || j == 7 || k == 0 || k == 1;
		}).pack();

		Producer<PackedCollection<?>> stride = c(p(input))
				.enumerate(0, 3, 1)
				.enumerate(1, 3, 1)
				.multiply(c(p(filter)));
	}
}
