/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class CollectionMathTests implements TestFeatures {
	@Test
	public void broadcastProduct1() {
		PackedCollection<?> a = new PackedCollection<>(shape(10));
		a.fill(pos -> Math.random());

		verboseLog(() -> {
			PackedCollection<?> result = cp(a).multiply(c(2.0)).get().evaluate();
			log(result.getShape().toStringDetail());

			for (int i = 0; i < 10; i++) {
				assertEquals(a.valueAt(i) * 2.0, result.valueAt(i));
			}
		});
	}

	@Test
	public void broadcastProduct2() {
		PackedCollection<?> a = new PackedCollection<>(shape(10));
		a.fill(pos -> Math.random());

		verboseLog(() -> {
			PackedCollection<?> result = new PackedCollection<>(shape(10));

			CollectionProducer<PackedCollection<?>> product =
					multiply(v(shape(-1), 0), v(shape(1), 1));
			product.get().into(result.each()).evaluate(a.each(), pack(2.0));

			result.print();

			for (int i = 0; i < 10; i++) {
				assertEquals(a.valueAt(i) * 2.0, result.valueAt(i));
			}
		});
	}

	@Test
	public void traverseProduct() {
		PackedCollection<?> a = new PackedCollection<>(shape(10)).randFill();
		PackedCollection<?> b = new PackedCollection<>(shape(10)).randFill();

		verboseLog(() -> {
			PackedCollection<?> result = cp(a).multiply(cp(b.traverse(1))).get().evaluate();
			System.out.println(result.getShape().toStringDetail());
			Assert.assertEquals(1, result.getShape().getTraversalAxis());

			for (int i = 0; i < 10; i++) {
				assertEquals(a.valueAt(i) * b.valueAt(i), result.valueAt(i));
			}
		});
	}

	@Test
	public void repeatProduct() {
		PackedCollection<?> a = new PackedCollection<>(shape(2, 5)).randFill();
		PackedCollection<?> b = new PackedCollection<>(shape(2)).randFill();

		verboseLog(() -> {
			PackedCollection<?> result = cp(a).multiply(cp(b)).get().evaluate();
			System.out.println(result.getShape().toStringDetail());

			for (int i = 0; i < 2; i++) {
				for (int j = 0; j < 5; j++) {
					assertEquals(a.valueAt(i, j) * b.valueAt(i), result.valueAt(i, j));
				}
			}
		});
	}

	@Test
	public void doubleBroadcastProduct() {
		int r = 6;
		int c = 40;

		PackedCollection<?> a = new PackedCollection<>(shape(r)).randFill();
		PackedCollection<?> b = new PackedCollection<>(shape(c)).randFill();

		PackedCollection<?> result = cp(a).multiply(cp(b).repeat(r)).get().evaluate();

		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				assertEquals(a.valueAt(i) * b.valueAt(j), result.valueAt(i, j));
			}
		}
	}

	@Test
	public void doubleBroadcastProductArguments() {
		int r = 3;
		int c = 4;

		// PackedCollection<?> a = pack(1.0, 0.1, 0.01);
		PackedCollection<?> a = new PackedCollection<>(shape(r)).randFill();
		// PackedCollection<?> b = pack(1, 2, 3, 4);
		PackedCollection<?> b = new PackedCollection<>(shape(c)).randFill();

		PackedCollection<?> result =
				cv(shape(r), 0).traverse(1).repeat(c)
					.multiply(cv(shape(c), 1).repeat(r))
				.get().evaluate(a, b);

		result.traverse(1).print();

		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				log(a.valueAt(i) + " * " + b.valueAt(j) + " = " + result.valueAt(i, j));
				assertEquals(a.valueAt(i) * b.valueAt(j), result.valueAt(i, j));
			}
		}
	}

	@Test
	public void sum() {
		int size = 768;

		PackedCollection<?> x = new PackedCollection<>(shape(size)).randFill();

		kernelTest(() -> c(p(x)).sum(),
				output -> {
					double expected = 0;

					for (int j = 0; j < size; j++) {
						expected += x.valueAt(j);
					}

					assertEquals(expected, output.valueAt(0));
				}, false, false, true);
	}

	@Test
	public void linear() {
		PackedCollection<?> out = linear(0, 5, 10).evaluate();
		Assert.assertEquals(10, out.getShape().length(0));
		assertEquals(0.0, out.valueAt(0));
		assertEquals(10.0 / 3.0, out.valueAt(6));
		assertEquals(5.0, out.valueAt(9));
	}

	@Test
	public void cumulativeProduct() {
		int steps = 300;
		double betaStart = 0.0001;
		double betaEnd = 0.02;
		CollectionProducer<PackedCollection<?>> inputs = linear(betaStart, betaEnd, steps);
		CollectionProducer<PackedCollection<?>> products =
				cumulativeProduct(c(1.0).subtract(inputs), false);

		double in[] = products.evaluate().toArray();
		double ad[] = sqrt(products).evaluate().toArray();
		double bd[] = sqrt(c(1.0).subtract(products)).evaluate().toArray();

		Assert.assertEquals(in.length, ad.length);
		Assert.assertEquals(in.length, bd.length);

		for (int i = 0; i < in.length; i++) {
			assertEquals(Math.sqrt(in[i]), ad[i]);
			assertEquals(Math.sqrt(1.0 - in[i]), bd[i]);
		}
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
				}, false, false, true);
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
					CollectionProducer<PackedCollection<?>> ss = pow(traverseEach(p(x)), c(2.0)).traverse(0).sum();
					ss = ss.divide(c(size)).add(c(1e-5));
					ss = c(1.0).divide(ss.pow(c(0.5)));
					return ss;
				},
				output -> {
					double ss = 0.0;
					for (int j = 0; j < size; j++) {
						ss += x.valueAt(j) * x.valueAt(j);
					}
					ss /= size;
					ss += 1e-5;
					ss = 1.0f / Math.sqrt(ss);

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
					CollectionProducer<PackedCollection<?>> ss = pow(traverseEach(p(x)), c(2.0)).traverse(0).sum();
					ss = ss.divide(c(size)).add(c(1e-5));
					ss = c(1.0).divide(ss.pow(c(0.5)));
					return multiply(traverseEach(p(weight)), traverseEach(p(x))).multiply(ss);
				},
				output -> {
					double ss = 0.0;
					for (int j = 0; j < size; j++) {
						ss += x.valueAt(j) * x.valueAt(j);
					}
					ss /= size;
					ss += 1e-5;
					ss = 1.0f / Math.sqrt(ss);
					// normalize and scale
					for (int j = 0; j < size; j++) {
						Assert.assertEquals(weight.valueAt(j) * (ss * x.valueAt(j)), output.valueAt(j), 1e-5);
					}
				}, false, false, true);
	}

	@Test
	public void mean() {
		int c = 5;
		int g = 4;
		int v = 10;

		TraversalPolicy shape = shape(c, g, v);

		PackedCollection<?> o = new PackedCollection<>(shape);
		o.fill(pos -> Math.random());

		kernelTest(() -> cp(o).mean(2),
				output -> {
					for (int i = 0; i < c; i++) {
						for (int j = 0; j < g; j++) {
							double sum = 0;
							for (int k = 0; k < v; k++) {
								sum += o.valueAt(i, j, k);
							}
							assertEquals(sum / v, output.valueAt(i, j));
						}
					}
				});
	}

	@Test
	public void subtractMean() {
		int c = 5;
		int g = 4;
		int v = 10;

		TraversalPolicy shape = shape(c, g, v);

		PackedCollection<?> o = new PackedCollection<>(shape);
		o.fill(pos -> Math.random());

		kernelTest(() -> cp(o).subtractMean(2),
				output -> {
					for (int i = 0; i < c; i++) {
						for (int j = 0; j < g; j++) {
							double sum = 0;
							for (int k = 0; k < v; k++) {
								sum += o.valueAt(i, j, k);
							}

							double mean = sum / v;

							for (int k = 0; k < v; k++) {
								assertEquals(o.valueAt(i, j, k) - mean, output.valueAt(i, j, k));
							}
						}
					}
				});
	}

	@Test
	public void subtractMeanSq() {
		int c = 5;
		int g = 4;
		int v = 10;

		TraversalPolicy shape = shape(c, g, v);

		PackedCollection<?> o = new PackedCollection<>(shape);
		o.fill(pos -> Math.random());

		kernelTest(() -> sq(cp(o).subtractMean(2)),
				output -> {
					for (int i = 0; i < c; i++) {
						for (int j = 0; j < g; j++) {
							double sum = 0;
							for (int k = 0; k < v; k++) {
								sum += o.valueAt(i, j, k);
							}

							double mean = sum / v;

							for (int k = 0; k < v; k++) {
								assertEquals(Math.pow(o.valueAt(i, j, k) - mean, 2), output.valueAt(i, j, k));
							}
						}
					}
				});
	}

	@Test
	public void variance1() {
		if (testDepth < 2) return;

		variance(1);
	}

	@Test
	public void variance2() {
		variance(5);
	}

	public void variance(int c) {
		int g = 4;
		int v = 10;

		TraversalPolicy shape = shape(c, g, v);

		PackedCollection<?> o = new PackedCollection<>(shape);
		o.fill(pos -> Math.random());

		kernelTest(() -> cp(o).variance(2),
				output -> {
					for (int i = 0; i < c; i++) {
						for (int j = 0; j < g; j++) {
							double sum = 0;
							for (int k = 0; k < v; k++) {
								sum += o.valueAt(i, j, k);
							}

							double mean = sum / v;

							double variance = 0;
							for (int k = 0; k < v; k++) {
								double d = o.valueAt(i, j, k) - mean;
								variance += d * d;
							}

							variance /= v;

							assertEquals(variance, output.valueAt(i, j));
						}
					}
				});
	}

	@Test
	public void meanVarianceQuotient() {
		int n = 1;
		int g = 4;
		int v = 4;

		TraversalPolicy shape = shape(n, g, v);

		PackedCollection<?> o = new PackedCollection<>(shape.getTotalSize());
		o.fill(pos -> Math.random());

		kernelTest(() -> {
					CollectionProducer<?> input = cp(o).reshape(-1, g, v);
					return input
							.subtractMean(2)
							.divide(input.variance(2))
							.reshape(-1, shape.getTotalSize());
				},
				output -> {
					output = output.reshape(n, g, v);
					PackedCollection<?> in = o.reshape(n, g, v);

					for (int i = 0; i < n; i++) {
						for (int j = 0; j < g; j++) {
							double sum = 0;
							for (int k = 0; k < v; k++) {
								sum += in.valueAt(i, j, k);
							}

							double mean = sum / v;

							double variance = 0;
							for (int k = 0; k < v; k++) {
								double d = in.valueAt(i, j, k) - mean;
								variance += d * d;
							}

							variance /= v;

							for (int k = 0; k < v; k++) {
								assertEquals((in.valueAt(i, j, k) - mean) / variance, output.valueAt(i, j, k));
							}
						}
					}
				});
	}

	@Test
	public void addInPlace() {
		int size = 10;

		PackedCollection<?> aOrig = new PackedCollection<>(shape(size));
		aOrig.fill(pos -> Math.random());

		PackedCollection<?> a = new PackedCollection<>(shape(size));
		a.fill(pos -> aOrig.valueAt(pos));

		PackedCollection<?> b = new PackedCollection<>(shape(size));
		b.fill(pos -> Math.random());

		Runnable op = (Runnable) a(traverseEach(p(a)), add(traverseEach(p(a)), traverseEach(p(b)))).optimize().get();
		op.run();

		for (int i = 0; i < size; i++) {
			Assert.assertEquals(aOrig.valueAt(i) + b.valueAt(i), a.valueAt(i), 1e-5);
		}
	}

	@Test
	public void lessThanSingleValue() {
		// Test lessThan with single scalar values
		Producer<PackedCollection<?>> a = c(5.0);
		Producer<PackedCollection<?>> b = c(10.0);

		// if a < b, return a, else return b
		Producer<PackedCollection<?>> result = lessThan(a, b, a, b);

		try (PackedCollection<?> value = result.get().evaluate()) {
			System.out.println("lessThan single: " + value.toDouble() + " (expected 5.0)");
			Assert.assertEquals(5.0, value.toDouble(), 0.001);
		}
	}

	@Test
	public void lessThanSmallBatch() {
		// Test lessThan with a small batch of 3 elements
		PackedCollection<?> valuesA = new PackedCollection<>(shape(3, 1).traverse(1));
		valuesA.setMem(0, 2.0);  // a[0] = 2.0
		valuesA.setMem(1, 8.0);  // a[1] = 8.0
		valuesA.setMem(2, 5.0);  // a[2] = 5.0

		PackedCollection<?> valuesB = new PackedCollection<>(shape(3, 1).traverse(1));
		valuesB.setMem(0, 7.0);  // b[0] = 7.0
		valuesB.setMem(1, 3.0);  // b[1] = 3.0
		valuesB.setMem(2, 5.0);  // b[2] = 5.0

		Producer a = v(shape(-1, 1), 0);
		Producer b = v(shape(-1, 1), 1);

		// if a < b, return a, else return b (essentially min(a, b))
		Producer result = lessThan(a, b, a, b);

		PackedCollection<?> resultData = new PackedCollection<>(shape(3, 1).traverse(1));

		result.get().into(resultData.each()).evaluate(valuesA, valuesB);

		System.out.println("lessThan small batch:");
		System.out.println("  [0]: " + resultData.valueAt(0, 0) + " (expected 2.0, min of 2.0 and 7.0)");
		System.out.println("  [1]: " + resultData.valueAt(1, 0) + " (expected 3.0, min of 8.0 and 3.0)");
		System.out.println("  [2]: " + resultData.valueAt(2, 0) + " (expected 5.0, min of 5.0 and 5.0)");

		Assert.assertEquals(2.0, resultData.valueAt(0, 0), 0.001);
		Assert.assertEquals(3.0, resultData.valueAt(1, 0), 0.001);
		Assert.assertEquals(5.0, resultData.valueAt(2, 0), 0.001);
	}

	@Test
	public void lessThanLargeBatch() {
		// Test lessThan with 256 elements to check for batch size limits
		int batchSize = 256;

		// Use separate inputs like lessThanSmallBatch (combined input format doesn't work with v(shape, argIndex))
		PackedCollection<?> valuesA = new PackedCollection<>(shape(batchSize, 1).traverse(1));
		PackedCollection<?> valuesB = new PackedCollection<>(shape(batchSize, 1).traverse(1));

		// Fill with test data: a[i] = i, b[i] = 255 - i
		// Expected: min(i, 255-i)
		for (int i = 0; i < batchSize; i++) {
			valuesA.setMem(i, (double) i);
			valuesB.setMem(i, (double) (255 - i));
		}

		Producer a = v(shape(-1, 1), 0);
		Producer b = v(shape(-1, 1), 1);

		// if a < b, return a, else return b
		Producer result = lessThan(a, b, a, b);

		PackedCollection<?> resultData = new PackedCollection<>(shape(batchSize, 1).traverse(1));
		result.get().into(resultData.each()).evaluate(valuesA, valuesB);

		System.out.println("lessThan large batch (size=" + batchSize + "):");
		System.out.println("  [0]: " + resultData.valueAt(0, 0) + " (expected 0.0)");
		System.out.println("  [100]: " + resultData.valueAt(100, 0) + " (expected 100.0)");
		System.out.println("  [127]: " + resultData.valueAt(127, 0) + " (expected 127.0)");
		System.out.println("  [128]: " + resultData.valueAt(128, 0) + " (expected 127.0)");
		System.out.println("  [200]: " + resultData.valueAt(200, 0) + " (expected 55.0)");
		System.out.println("  [255]: " + resultData.valueAt(255, 0) + " (expected 0.0)");

		// Check key values
		Assert.assertEquals(0.0, resultData.valueAt(0, 0), 0.001);
		Assert.assertEquals(100.0, resultData.valueAt(100, 0), 0.001);
		Assert.assertEquals(127.0, resultData.valueAt(127, 0), 0.001);
		Assert.assertEquals(127.0, resultData.valueAt(128, 0), 0.001);  // crossover point
		Assert.assertEquals(55.0, resultData.valueAt(200, 0), 0.001);
		Assert.assertEquals(0.0, resultData.valueAt(255, 0), 0.001);
	}
}
