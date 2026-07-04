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
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.GradientTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Tests for traversable delta computations.
 */
public class TraversableDeltaComputationTests extends TestSuiteBase implements GradientTestFeatures {

	/**
	 * Tests sum and power combination gradient.
	 */
	@Test(timeout = 60000)
	public void sumPow1() {
		PackedCollection o = new PackedCollection(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input.sum().pow(2.0).repeat(2)
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					double a = o.valueAt(0);
					double b = o.valueAt(1);

					assertEquals(2 * a + 2 * b, output.valueAt(0, 0));
					assertEquals(2 * b + 2 * a, output.valueAt(0, 1));
					assertEquals(2 * a + 2 * b, output.valueAt(1, 0));
					assertEquals(2 * b + 2 * a, output.valueAt(1, 1));
				});
	}

	/**
	 * Tests product and sum combination gradient.
	 */
	@Test(timeout = 60000)
	public void productSum1() {
		PackedCollection o = new PackedCollection(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input
							.multiply(input.sum().repeat(2))
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					double a = o.valueAt(0);
					double b = o.valueAt(1);

					assertEquals(2 * a + b, output.valueAt(0, 0));
					assertEquals(a, output.valueAt(0, 1));
					assertEquals(b, output.valueAt(1, 0));
					assertEquals(2 * b + a, output.valueAt(1, 1));
				});
	}

	/**
	 * Tests product, sum and power combination gradient.
	 */
	@Test(timeout = 60000)
	public void productSumPow1() {
		PackedCollection o = new PackedCollection(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input
							.multiply(input.sum().pow(2.0).repeat(2))
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					double a = o.valueAt(0);
					double b = o.valueAt(1);

					assertEquals(3 * a * a + b * b + 4 * a * b, output.valueAt(0, 0));
					assertEquals(2 * a * b + 2 * a * a, output.valueAt(0, 1));
					assertEquals(2 * a * b + 2 * b * b, output.valueAt(1, 0));
					assertEquals(a * a + 3 * b * b + 4 * a * b, output.valueAt(1, 1));
				});
	}

	/**
	 * Helper method to test variance gradient computation.
	 * @param name the test name for profiling
	 * @param x first input value
	 * @param y second input value
	 * @return the operation profile node
	 */
	public OperationProfileNode variance(String name, double x, double y) {
		int c = 2;
		int groups = 1;

		PackedCollection o = new PackedCollection(c).fill(x, y);

		return kernelTest(name, () -> {
					CollectionProducer input = cp(o).reshape(-1, groups, c / groups);
					CollectionProducer out = input.variance().repeat(c)
							.reshape(-1, c);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					for (int i = 0; i < 2; i++) {
						for (int j = 0; j < 2; j++) {
							double out = output.valueAt(i, j);
							double k0 = o.valueAt(0);
							double k1 = o.valueAt(1);

							double expected;

							if (j == 0) {
								expected = (k0 - k1) / 2;
							} else {
								expected = (k1 - k0) / 2;
							}

							log(expected + " vs " + out);
							assertEquals(expected, out);
						}
					}
				}, false, false, true);
	}

	/**
	 * Tests variance gradient.
	 */
	@Test(timeout = 60000)
	public void variance1() throws IOException {
		variance("variance1", 1.0, 1.1).save("results/variance1.xml");
	}

	/**
	 * Tests variance gradient with different inputs.
	 */
	@Test(timeout = 60000)
	public void variance2() throws IOException {
		variance("variance2", 1.5, 2.5).save("results/variance2.xml");
	}

	/**
	 * Tests divide by mean gradient.
	 */
	@Test(timeout = 60000)
	public void divide1() {
		PackedCollection o = new PackedCollection(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input
							.divide(input.mean().repeat(2))
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					double k0 = o.valueAt(0);
					double k1 = o.valueAt(1);

					double sum = k0 + k1;
					double sumSquared = sum * sum;

					assertEquals(2 * k1 / sumSquared, output.valueAt(0, 0));
					assertEquals(-2 * k0 / sumSquared, output.valueAt(0, 1));
					assertEquals(-2 * k1 / sumSquared, output.valueAt(1, 0));
					assertEquals(2 * k0 / sumSquared, output.valueAt(1, 1));
				});
	}

	/**
	 * Tests divide by squared mean gradient.
	 */
	@Test(timeout = 60000)
	public void divide2() {
		PackedCollection o = new PackedCollection(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input
							.divide(input.sq().mean().repeat(2))
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					double k0 = o.valueAt(0);
					double k1 = o.valueAt(1);

					double sumSquares = k0 * k0 + k1 * k1;
					double sumSquaresSquared = sumSquares * sumSquares;

					assertEquals(2 * (k1 * k1 - k0 * k0) / sumSquaresSquared, output.valueAt(0, 0));
					assertEquals(-4 * k0 * k1 / sumSquaresSquared, output.valueAt(0, 1));
					assertEquals(-4 * k0 * k1 / sumSquaresSquared, output.valueAt(1, 0));
					assertEquals(2 * (k0 * k0 - k1 * k1) / sumSquaresSquared, output.valueAt(1, 1));
				});
	}

	/**
	 * Tests subtract mean and divide gradient.
	 */
	@Test(timeout = 60000)
	public void divide3() {
		PackedCollection o = new PackedCollection(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input.subtractMean()
							.divide(input.sq().mean().repeat(2))
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					double k0 = o.valueAt(0);
					double k1 = o.valueAt(1);

					double sumSquares = k0 * k0 + k1 * k1;
					double sumSquaresSquared = sumSquares * sumSquares;

					assertEquals((-k0 * k0 + k1 * k1 + 2 * k0 * k1) / sumSquaresSquared, output.valueAt(0, 0));
					assertEquals((-k0 * k0 + k1 * k1 - 2 * k0 * k1) / sumSquaresSquared, output.valueAt(0, 1));
					assertEquals((k0 * k0 - k1 * k1 - 2 * k0 * k1) / sumSquaresSquared, output.valueAt(1, 0));
					assertEquals((k0 * k0 - k1 * k1 + 2 * k0 * k1) / sumSquaresSquared, output.valueAt(1, 1));
				});
	}

	/**
	 * Tests divide with epsilon sqrt gradient.
	 */
	@Test(timeout = 60000)
	public void divide4() {
		PackedCollection o = new PackedCollection(2).fill(1.0, 1.01);
		double eps = 1e-5;

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input
							.divide(input.add(c(eps)).sqrt())
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					for (int i = 0; i < 2; i++) {
						for (int j = 0; j < 2; j++) {
							double x = o.valueAt(i);
							double actual = output.valueAt(i, j);
							double expected = 0.0;

							if (i == j) {
								expected = (x + 2 * eps) / (2 * Math.pow(x + eps, 3.0 / 2.0));
							}

							assertEquals(expected, actual);
						}
					}
				});
	}

	/**
	 * Tests divide with squared epsilon sqrt gradient.
	 */
	@Test(timeout = 60000)
	public void divide5() {
		PackedCollection o = new PackedCollection(2).fill(1.0, 1.01);
		double eps = 1e-5;

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input
							.divide(input.sq().add(c(eps)).sqrt())
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					for (int i = 0; i < 2; i++) {
						for (int j = 0; j < 2; j++) {
							double x = o.valueAt(i);
							double actual = output.valueAt(i, j);
							double expected = 0.0;

							if (i == j) {
								expected = eps / (Math.pow(x * x + eps, 3.0 / 2.0));
								log(expected + " vs " + actual);
							}

							assertEquals(expected, actual);
						}
					}
				}, false, false, true);
	}

	/**
	 * Tests subtract mean divide by variance gradient.
	 */
	@Test(timeout = 60000)
	public void divide6() {
		PackedCollection o = new PackedCollection(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input.subtractMean()
							.divide(input.variance())
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					double k0 = o.valueAt(0);
					double k1 = o.valueAt(1);

					double diffSquared = Math.pow(k0 - k1, 2);

					assertEquals(-2 / diffSquared, output.valueAt(0, 0));
					assertEquals(2 / diffSquared, output.valueAt(0, 1));
					assertEquals(2 / diffSquared, output.valueAt(1, 0));
					assertEquals(-2 / diffSquared, output.valueAt(1, 1));
				}, false, false, true);
	}

	/**
	 * Runs recursive division tests with the given supply and validation.
	 * @param supply the factor supplying input collections
	 * @param validate the consumer to validate output
	 */
	protected void recursiveDivisionTest(Factor<PackedCollection> supply,
										 BiConsumer<PackedCollection, PackedCollection> validate) {
		recursiveDivisionTest(supply, validate, false);
	}

	/**
	 * Runs recursive division tests with optimization control.
	 * @param supply the factor supplying input collections
	 * @param validate the consumer to validate output
	 * @param optimizeOnly if true, only run with optimization
	 */
	protected void recursiveDivisionTest(Factor<PackedCollection> supply,
										 BiConsumer<PackedCollection, PackedCollection> validate,
										 boolean optimizeOnly) {
		double x = 1.0;
		double y = 1.02 * Math.pow(2, 5);

		PackedCollection o = new PackedCollection(2);

		for (int i = 0; i < 6; i++) {
			y = y / 2.0;
			o.fill(x, y);

			log("Iteration " + i + " y = " + y);
			kernelTest(
					() -> supply.getResultant(cp(o)),
					out -> validate.accept(o, out),
					!optimizeOnly, !optimizeOnly, true);
		}
	}

	/**
	 * Tests recursive division with subtract mean and variance sqrt.
	 */
	@Test(timeout = 60000)
	@TestDepth(1)
	public void divide7() {
		recursiveDivisionTest(in -> {
					CollectionProducer input = c(in).reshape(-1, 1, 2);
					CollectionProducer out = input.subtractMean()
							.divide(input.variance().sqrt())
							.reshape(-1, 2);
					return out.delta(input);
				},
				(input, output) -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					assertEquals(0.0, output.valueAt(0, 0));
					assertEquals(0.0, output.valueAt(0, 1));
					assertEquals(0.0, output.valueAt(1, 0));
					assertEquals(0.0, output.valueAt(1, 1));
				});
	}

	/**
	 * Tests recursive division with bias addition.
	 */
	@Test(timeout = 60000)
	@TestDepth(1)
	public void divide8() {
		PackedCollection b = new PackedCollection(2);

		recursiveDivisionTest(in -> {
					CollectionProducer input = c(in).reshape(-1, 1, 2);
					CollectionProducer out = input.subtractMean()
							.divide(input.variance().sqrt())
							.reshape(-1, 2)
							.add(cp(b));
					return out.delta(input);
				},
				(input, output) -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					assertEquals(0.0, output.valueAt(0, 0));
					assertEquals(0.0, output.valueAt(0, 1));
					assertEquals(0.0, output.valueAt(1, 0));
					assertEquals(0.0, output.valueAt(1, 1));
				});
	}

	/**
	 * Tests divide with mean subtraction and epsilon.
	 */
	@Test(timeout = 60000)
	public void divide9() {
//		PackedCollection o = new PackedCollection(2).fill(1.0, 1.01);
		PackedCollection o = new PackedCollection(2).fill(1.0, 10);
		double eps = 1e-5;

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, 2);
					CollectionProducer out = input.subtractMean()
							.divide(mean(input.subtractMean()).add(c(eps)).sqrt())
							.reshape(-1, 2);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();
				});
	}

	/**
	 * Tests recursive divide with squared diff and epsilon.
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void divide10() {
		double eps = 1e-5;

		recursiveDivisionTest(in -> {
					CollectionProducer input = c(in).reshape(-1, 1, 2);
					CollectionProducer out = input.subtractMean()
							.divide(mean(sq(subtractMean(input))).add(c(eps)).sqrt())
							.reshape(-1, 2);
					return out.delta(input);
				},
				(input, output) -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					double diff = input.valueAt(0) - input.valueAt(1);
					double denominator = Math.pow(diff * diff + 4 * eps, 1.5);

					assertEquals(4 * eps / denominator, output.valueAt(0, 0));
					assertEquals(-4 * eps / denominator, output.valueAt(0, 1));
					assertEquals(-4 * eps / denominator, output.valueAt(1, 0));
					assertEquals( 4 * eps / denominator, output.valueAt(1, 1));
				});
	}

	/**
	 * Tests recursive divide with bias and epsilon.
	 */
	@Test(timeout = 60000)
	@TestDepth(1)
	public void divide11() {
		double eps = 1e-5;

		PackedCollection b = new PackedCollection(2);

		recursiveDivisionTest(in -> {
					CollectionProducer input = c(in).reshape(-1, 1, 2);
					CollectionProducer out = input.subtractMean()
							.divide(mean(sq(subtractMean(input))).add(c(eps)).sqrt())
							.reshape(-1, 2)
							.add(cp(b));
					return out.delta(input);
				},
				(input, output) -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					double diff = input.valueAt(0) - input.valueAt(1);
					double denominator = Math.pow(diff * diff + 4 * eps, 1.5);

					assertEquals(4 * eps / denominator, output.valueAt(0, 0));
					assertEquals(-4 * eps / denominator, output.valueAt(0, 1));
					assertEquals(-4 * eps / denominator, output.valueAt(1, 0));
					assertEquals( 4 * eps / denominator, output.valueAt(1, 1));
				}, true);
	}

	/**
	 * Tests divide product gradient with random inputs.
	 */
	@Test(timeout = 60000)
	@TestDepth(1)
	public void divideProduct1() {
		int c = 2;

		PackedCollection o = new PackedCollection(c).fill(() -> Math.random() / 10.0);
		PackedCollection g = new PackedCollection(c).fill(() -> Math.random() / 4.0);
		double eps = 1e-5;

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, 1, c);
					CollectionProducer out = input.subtractMean()
							.divide(mean(sq(subtractMean(input))).add(c(eps)).sqrt())
							.reshape(-1, c);
					out = out.delta(input);
					return applyGradient(out, cp(g));
				},
				output -> {
					output = output.reshape(c);
					output.print();

					double muG = o.doubleStream().sum() / c;
					double varG = variance(cp(o)).evaluate().toDouble();
					double stdG = Math.sqrt(varG + eps);

					PackedCollection normalized =
							cp(o).subtract(c(muG))
									.divide(c(stdG))
										.evaluate();

					double gradientMean = g.doubleStream().sum() / c;
					PackedCollection gradientByInput = cp(g).multiply(cp(normalized)).evaluate();

					double gradientByInputMean = gradientByInput.doubleStream().sum() / c;
					PackedCollection dLdXGroup = dlDxGroup(
							g, gradientMean, normalized, gradientByInputMean);

					for (int i = 0; i < c; i++) {
						double expected = dLdXGroup.valueAt(i) / stdG;
						double actual = output.valueAt(i);

						log(expected + " vs " + actual);
						assertSimilar(expected, actual);
					}
				});
	}

	/**
	 * Tests divide product with bias and profiling.
	 */
	@Test(timeout = 60000)
	public void divideProduct2() throws IOException {
		int c = 2;

		PackedCollection o = new PackedCollection(c).fill(() -> Math.random() / 10.0);
		PackedCollection g = new PackedCollection(c).fill(() -> Math.random() / 4.0);
		PackedCollection b = new PackedCollection(c).fill(0.0);
		double eps = 1e-5;

		kernelTest("divideProduct2", () -> {
					CollectionProducer input = cp(o).reshape(-1, 1, c);
					CollectionProducer out = input.subtractMean()
							.divide(mean(sq(subtractMean(input))).add(c(eps)).sqrt())
							.reshape(-1, c)
							.add(cp(b));
					out = out.delta(input);
					return applyGradient(out, cp(g));
				},
				output -> {
					output = output.reshape(c);
					output.print();

					double muG = o.doubleStream().sum() / c;
					double varG = variance(cp(o)).evaluate().toDouble();
					double stdG = Math.sqrt(varG + eps);

					PackedCollection normalized =
							cp(o).subtract(c(muG))
									.divide(c(stdG))
									.evaluate();

					double gradientMean = g.doubleStream().sum() / c;
					PackedCollection gradientByInput = cp(g).multiply(cp(normalized)).evaluate();

					double gradientByInputMean = gradientByInput.doubleStream().sum() / c;
					PackedCollection dLdXGroup = dlDxGroup(
							g, gradientMean, normalized, gradientByInputMean);

					for (int i = 0; i < c; i++) {
						double expected = dLdXGroup.valueAt(i) / stdG;
						double actual = output.valueAt(i);
						assertEquals(expected, actual);
					}
				}, false, true, true).save("results/divideProduct2.xml");
	}

	/**
	 * Tests divide product with random source generator.
	 */
	@Test(timeout = 60000)
	public void divideProduct3() throws IOException {
		int c = 2;
		divideProduct("divideProduct3", c, () -> new PackedCollection(c).fill(() -> Math.random() / 10.0));
	}

	/**
	 * Tests divide product with specific values.
	 */
	@Test(timeout = 60000)
	public void divideProduct4() throws IOException {
		int c = 2;
		divideProduct("divideProduct4", c, () -> new PackedCollection(c).fill(1.0, 1.01));
	}

	/**
	 * Tests divide product gradient with configurable source and saves profile.
	 * @param name the test name for profiling
	 * @param c the collection size
	 * @param source the supplier for input collections
	 * @throws IOException if saving the profile fails
	 */
	public void divideProduct(String name, int c, Supplier<PackedCollection> source) throws IOException {
		PackedCollection o = source.get();
		PackedCollection g = new PackedCollection(c).fill(() -> 1 + (Math.random() * 4.0));
		PackedCollection w = new PackedCollection(c).fill(1.0);
		PackedCollection b = new PackedCollection(c).fill(0.0);
		double eps = 1e-5;

		kernelTest(name, () -> {
					CollectionProducer input = cp(o).reshape(-1, 1, c);
					CollectionProducer out = input.subtractMean()
							.divide(mean(sq(subtractMean(input))).add(c(eps)).sqrt())
							.reshape(-1, c)
							.multiply(cp(w))
							.add(cp(b));
					out = out.delta(input);
					return applyGradient(out, cp(g));
				},
				output -> {
					output = output.reshape(c);
					output.print();

					PackedCollection result = normBackwards(o, g, null, null);

					for (int i = 0; i < c; i++) {
						double expected = result.valueAt(i);
						double actual = output.valueAt(i);
						// log(expected + " vs " + actual);

						assertSimilar(expected, actual);
					}
				}, false, false, true).save("results/" + name + ".xml");
	}

	/**
	 * Tests enumerate operation gradient.
	 */
	@Test(timeout = 60000)
	public void enumerate() {
		int count = 1;
		int dim = 2;

		PackedCollection v = pack(2.0, 3.0, 2.0, 3.0)
				.reshape(count, 1, dim, dim).traverse();

		CollectionProducer cdy = cp(v)
				.reshape(count, dim * dim)
				.enumerate(1, 1)
				.delta(p(v));
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate();
		print(4, 4, dout);

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				if (i == j) {
					assertEquals(1.0, dout.toDouble(i * 4 + j));
				} else {
					assertEquals(0.0, dout.toDouble(i * 4 + j));
				}
			}
		}
	}

	/**
	 * Tests embedded gradient with multiple weights.
	 */
	@Test(timeout = 60000)
	public void embedded1() {
		int dim = 3;
		int count = 2;

		PackedCollection v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection w1 = pack(4, -3, 2);
		PackedCollection w2 = pack(2, 1, 5);
		CollectionProducer x = x(-1, dim);

		// w2 * w1 * x
		CollectionProducer c = x.mul(p(w1)).mul(p(w2));

		// dy = f'(x)
		//    = w2 * w1
		Evaluable<PackedCollection> dy = c.delta(x).get();
		PackedCollection dout = dy.evaluate(v);
		log(dout.getShape());
		dout.print();

		for (int i = 0; i < count; i++) {
			for (int j = 0 ; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(w1.toDouble(j) * w2.toDouble(j), dout.toDouble(i * dim * dim + j * dim + k));
					} else {
						assertEquals(0.0, dout.toDouble(i * dim * dim + j * dim + k));
					}
				}
			}
		}
	}

	/**
	 * Tests embedded gradient with sum.
	 */
	@Test(timeout = 60000)
	public void embedded2() {
		int dim = 3;

		PackedCollection w1 = pack(4, -3, 2);
		CollectionProducer x = cp(pack(0.0, 0.0, 0.0));

		// w0 * x0 + w1 * x1 + w2 * x2
		CollectionProducer c = x.mul(p(w1)).sum();

		// x.mul(p(w1)).delta(x).traverse(1).sum().evaluate().print();

		// dy = f'(x)
		//    = w0, w1, w2
		Evaluable<PackedCollection> dy = c.delta(x).get();
		PackedCollection dout = dy.evaluate();
		dout.print();

		for (int i = 0; i < dim; i++) {
			assertEquals(w1.toDouble(i), dout.toDouble(i));
		}
	}

	/**
	 * Tests repeat multiply with matrix and vector.
	 */
	@Test(timeout = 60000)
	public void repeatMultiply1() {
		int dim = 2;

		PackedCollection matrix = pack(2.0, 3.0, 4.0, 5.0).reshape(dim, dim);
		PackedCollection vector = pack(4.0, -3.0).reshape(shape(dim));

		verboseLog(() -> {
			CollectionProducer c = multiply(traverseEach(cp(matrix)), traverseEach(repeat(dim, cp(vector))));
			PackedCollection out = c.delta(cp(vector)).evaluate();
			log(out.getShape().toStringDetail());
			out.print();

			assertEquals(2.0, out.toDouble(0));
			assertEquals(0.0, out.toDouble(1));
			assertEquals(0.0, out.toDouble(2));
			assertEquals(3.0, out.toDouble(3));
			assertEquals(4.0, out.toDouble(4));
			assertEquals(0.0, out.toDouble(5));
			assertEquals(0.0, out.toDouble(6));
			assertEquals(5.0, out.toDouble(7));
		});
	}

	/**
	 * Tests repeat multiply with direct input.
	 */
	@Test(timeout = 60000)
	public void repeatMultiply2() {
		int dim = 2;

		PackedCollection matrix = pack(2.0, 3.0, 4.0, 5.0).reshape(dim, dim);
		PackedCollection vector = pack(4.0, -3.0).reshape(shape(dim));

		CollectionProducer c = multiply(traverseEach(cp(matrix)), traverseEach(repeat(dim, x(dim))));
		PackedCollection out = c.delta(x(dim)).evaluate(vector);
		log(out.getShape().toStringDetail());
		out.print();

		assertEquals(2.0, out.toDouble(0));
		assertEquals(0.0, out.toDouble(1));
		assertEquals(0.0, out.toDouble(2));
		assertEquals(3.0, out.toDouble(3));
		assertEquals(4.0, out.toDouble(4));
		assertEquals(0.0, out.toDouble(5));
		assertEquals(0.0, out.toDouble(6));
		assertEquals(5.0, out.toDouble(7));
	}

	/**
	 * Tests repeat multiply gradient w.r.t. matrix.
	 */
	@Test(timeout = 60000)
	public void repeatMultiply3() {
		int dim = 2;

		PackedCollection matrix = pack(2.0, 3.0, 4.0, 5.0).reshape(dim, dim);
		PackedCollection vector = pack(4.0, -3.0).reshape(shape(1, dim));

		CollectionProducer c = multiply(traverseEach(cp(matrix)), traverseEach(repeat(dim, x(dim))));
		PackedCollection out = c.delta(cp(matrix)).evaluate(vector.traverse());
		log(out.getShape().toStringDetail());
		out.print();

		for (int i = 0; i < (dim * dim); i++) {
			for (int j = 0; j < (dim * dim); j++) {
				if (i == j) {
					assertEquals(vector.toDouble(i % 2), out.toDouble(i * dim * dim + j));
				} else {
					assertEquals(0.0, out.toDouble(i * dim * dim + j));
				}
			}
		}
	}

	/**
	 * Tests multiply gradient.
	 */
	@Test(timeout = 60000)
	public void multiply() {
		int dim = 4;

		PackedCollection v = new PackedCollection(shape(dim)).randFill();
		PackedCollection f = new PackedCollection(shape(dim)).randFill();

		CollectionProducer cdy = cp(v).multiply(p(f))
				.delta(p(v));
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate().reshape(dim, dim);
		dout.traverse().print();

		for (int i = 0; i < dim; i++) {
			for (int j = 0; j < dim; j++) {
				if (i == j) {
					assertEquals(f.valueAt(j), dout.valueAt(i, j));
				} else {
					assertEquals(0.0, dout.valueAt(i, j));
				}
			}
		}
	}

	/**
	 * Tests multiply add gradient.
	 */
	@Test(timeout = 60000)
	public void multiplyAdd1() {
		int dim = 4;

		PackedCollection v = new PackedCollection(shape(dim)).randFill();
		PackedCollection f = new PackedCollection(shape(dim)).randFill();
		PackedCollection g = new PackedCollection(shape(dim)).randFill();

		CollectionProducer cdy = cp(v).multiply(cp(f).add(cp(g)))
				.delta(p(f));
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate().reshape(dim, dim);
		dout.traverse().print();

		for (int i = 0; i < dim; i++) {
			for (int j = 0; j < dim; j++) {
				if (i == j) {
					assertEquals(v.valueAt(j), dout.valueAt(i, j));
				} else {
					assertEquals(0.0, dout.valueAt(i, j));
				}
			}
		}
	}

	/**
	 * Tests multiply self-add gradient.
	 */
	@Test(timeout = 60000)
	public void multiplyAdd2() {
		int dim = 4;

		PackedCollection f = new PackedCollection(shape(dim)).randFill();
		PackedCollection g = new PackedCollection(shape(dim)).randFill();

		// y = f * (f + g) = f^2 + f * g
		CollectionProducer cdy = cp(f).multiply(cp(f).add(cp(g)))
				.delta(p(f));
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate().reshape(dim, dim);
		dout.traverse().print();

		for (int i = 0; i < dim; i++) {
			for (int j = 0; j < dim; j++) {
				if (i == j) {
					assertEquals(2.0 * f.valueAt(j) + g.valueAt(j), dout.valueAt(i, j));
				} else {
					assertEquals(0.0, dout.valueAt(i, j));
				}
			}
		}
	}

	/**
	 * Tests multiply sum gradient.
	 */
	@Test(timeout = 60000)
	public void multiplySum() {
		int dim = 4;

		PackedCollection v = new PackedCollection(shape(dim)).randFill();
		PackedCollection f = new PackedCollection(shape(dim)).randFill();

		CollectionProducer cdy = cp(v).multiply(p(f))
				.delta(p(v))
				.sum(1);
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate();
		log(dout.getShape().toStringDetail());
		dout.print();

		for (int n = 0; n < dim; n++) {
			assertEquals(f.toDouble(n), dout.toDouble(n));
		}
	}

	/**
	 * Tests multiply enumerate gradient.
	 */
	@Test(timeout = 60000)
	public void multiplyEnumerate() {
		int dim = 2;

		PackedCollection v = pack(2.0, 3.0, 2.0, 3.0)
				.reshape(dim, dim);
		PackedCollection f = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		CollectionProducer cdy = cp(v).multiply(p(f))
				.enumerate(1, 1)
				.delta(p(v));
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate();
		print(4, 4, dout);

		for (int n = 0; n < 4; n++) {
			int i = (2 * n) % 4 + n / 2;

			for (int j = 0; j < 4; j++) {
				if (n == j) {
					assertEquals(f.toDouble(j), dout.toDouble(i * 4 + j));
				} else {
					assertEquals(0.0, dout.toDouble(i * 4 + j));
				}
			}
		}
	}

	/**
	 * Tests enumerate index gradient.
	 */
	@Test(timeout = 60000)
	public void enumerateIndex() {
		int dim = 2;

		PackedCollection v = pack(2.0, 3.0, 2.0, 3.0)
				.reshape(dim, dim);
		PackedCollection f = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		CollectionProducer cdy = cp(v).multiply(p(f)).enumerate(1, 1);
		// cdy = ((IndexProjectionProducerComputation) cdy).getIndex();
		cdy = cdy.delta(p(v));

		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate();
		print(4, 4, dout);

		for (int n = 0; n < 4; n++) {
			int i = (2 * n) % 4 + n / 2;

			for (int j = 0; j < 4; j++) {
				if (n == j) {
					assertEquals(f.toDouble(j), dout.toDouble(i * 4 + j));
				} else {
					assertEquals(0.0, dout.toDouble(i * 4 + j));
				}
			}
		}
	}

	/**
	 * Tests enumerate multiply sum gradient.
	 */
	@Test(timeout = 60000)
	public void enumerateMultiplySum() {
		boolean enableSum = true;
		int count = 1;
		int dim = 2;

		PackedCollection v = pack(2.0, 3.0, 2.0, 3.0)
				.reshape(count, 1, dim, dim).traverse();
		PackedCollection f = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		CollectionProducer cdy = cp(v)
				.reshape(count, dim * dim)
				.enumerate(1, 1)
				.delta(p(v))
				.reshape(dim * dim, dim * dim)
				.traverse(1)
				.multiply(cp(f).reshape(dim * dim).traverse(1).expand(dim * dim));
		if (enableSum) cdy = cdy.sum(1);
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate();
		print(1, 4, dout);

		if (enableSum) {
			for (int i = 0; i < 4; i++) {
				assertEquals(f.toDouble(i), dout.toDouble(i));
			}
		} else {
			for (int i = 0; i < 4; i++) {
				for (int j = 0; j < 4; j++) {
					if (i == j) {
						assertEquals(f.toDouble(i), dout.toDouble(i * 4 + j));
					} else {
						assertEquals(0.0, dout.toDouble(i * 4 + j));
					}
				}
			}
		}
	}

	/**
	 * Tests enumerate map gradient with optimization.
	 */
	@Test(timeout = 60000)
	public void enumerateMap() {
		boolean enableOptimize = true;
		boolean enableSum = true;
		int count = 1;
		int dim = 2;

		ParallelProcess.explicitIsolationTargets.add(operationFilter("Enumerate"));

		try {
			PackedCollection v = pack(2.0, 3.0, 2.0, 3.0)
					.reshape(count, 1, dim, dim).traverse();
			PackedCollection f = pack(4.0, -3.0, 2.0, 1.5)
					.reshape(shape(dim, dim));

			CollectionProducer cdy = cp(v)
					.reshape(count, dim * dim)
					.enumerate(1, 1)
					.traverseEach()
					.multiply(2.0)
					.delta(p(v))
					.reshape(dim * dim, dim * dim)
					.traverse(1)
					.multiply(cp(f).reshape(dim * dim).traverse(1).expand(dim * dim));
			if (enableSum) cdy = cdy.sum(1);
			if (enableOptimize) cdy = (CollectionProducer) Process.optimized(cdy);
			Evaluable<PackedCollection> dy = cdy.get();
			PackedCollection dout = dy.evaluate();
			print(1, 4, dout);

			if (enableSum) {
				for (int i = 0; i < 4; i++) {
					assertEquals(2 * f.toDouble(i), dout.toDouble(i));
				}
			} else {
				for (int i = 0; i < 4; i++) {
					for (int j = 0; j < 4; j++) {
						if (i == j) {
							assertEquals(2 * f.toDouble(i), dout.toDouble(i * 4 + j));
						} else {
							assertEquals(0.0, dout.toDouble(i * 4 + j));
						}
					}
				}
			}
		} finally {
			ParallelProcess.explicitIsolationTargets.clear();
		}
	}

	/**
	 * Tests multiply twice with small dimension.
	 */
	@Test(timeout = 60000)
	public void multiplyTwiceSmall() {
		multiplyTwice(2, false);
	}

	/**
	 * Tests multiply twice with large dimension.
	 */
	@Test(timeout = 60000)
	public void multiplyTwiceLarge() {
		multiplyTwice(5, false);
	}

	/**
	 * Tests multiply operation applied twice with specified dimension.
	 * @param dim the dimension for the test
	 * @param optimize whether to use optimized evaluation
	 */
	public void multiplyTwice(int dim, boolean optimize) {
		PackedCollection input = new PackedCollection(shape(dim));
		CollectionProducer c = cp(input)
				.multiply(3)
				.multiply(2);

		HardwareOperator.verboseLog(() -> {
			CollectionProducer dy = c.delta(cp(input));
			PackedCollection dout;

			if (optimize) {
				dout = Process.optimized(dy).get().evaluate();
			} else {
				dout = dy.get().evaluate();
			}

			dout.traverse().print();

			for (int i = 0; i < dim; i++) {
				for (int j = 0; j < dim; j++) {
					if (i == j) {
						assertEquals(6.0, dout.toDouble(i * dim + j));
					} else {
						assertEquals(0.0, dout.toDouble(i * dim + j));
					}
				}
			}
		});
	}

	/**
	 * Tests sum then multiply gradient.
	 */
	@Test(timeout = 60000)
	public void sumMultiply() {
		int dim = 2;

		PackedCollection input = new PackedCollection(shape(dim, dim));
		CollectionProducer c = cp(input)
				.sum(1)
				.multiply(2);

		verboseLog(() -> {
			CollectionProducer dy = c.delta(cp(input));
			// PackedCollection dout = Process.optimized(dy).get().evaluate();
			PackedCollection dout = dy.get().evaluate();
			dout.print();

			dout = dout.reshape(shape(2, 4));

			assertEquals(2.0, dout.valueAt(0, 0));
			assertEquals(2.0, dout.valueAt(0, 1));
			assertEquals(0.0, dout.valueAt(0, 2));
			assertEquals(0.0, dout.valueAt(0, 3));
			assertEquals(0.0, dout.valueAt(1, 0));
			assertEquals(0.0, dout.valueAt(1, 1));
			assertEquals(2.0, dout.valueAt(1, 2));
			assertEquals(2.0, dout.valueAt(1, 3));
		});
	}

	/**
	 * Tests enumerate multiply with scaling.
	 */
	@Test(timeout = 60000)
	public void enumerateMultiply() {
		int dim = 5;
		int size = 2;

		PackedCollection input = new PackedCollection(shape(dim, dim));
		CollectionProducer c = cp(input)
				.enumerate(1, size, 1)
				.multiply(2);

		CollectionProducer dy = c.delta(cp(input));
		PackedCollection dout = Process.optimized(dy).get().evaluate();
		assertEquals(80, dout.doubleStream().sum());
	}

	/**
	 * Tests enumerate sum with multiple samples.
	 */
	@Test(timeout = 60000)
	public void enumerateSum1() {
		int count = 2;
		int dim = 3;

		PackedCollection v = pack(2.0, 3.0, 4.0,
											2.0, 3.0, 4.0,
											2.0, 3.0, 4.0,
											5.0, 6.0, 7.0,
											5.0, 6.0, 7.0,
											5.0, 6.0, 7.0)
				.reshape(count, 1, dim, dim).traverse();

		CollectionProducer cdy = cp(v)
				.reshape(count, dim * dim)
											.enumerate(1, 1)
											.sum(1)
											.reshape(3, 3);
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate();
		dout.print();

		assertEquals(7.0, dout.toDouble(0));
		assertEquals(9.0, dout.toDouble(1));
		assertEquals(11.0, dout.toDouble(2));
		assertEquals(7.0, dout.toDouble(3));
		assertEquals(9.0, dout.toDouble(4));
		assertEquals(11.0, dout.toDouble(5));
	}

	/**
	 * Tests enumerate sum with various dimensions.
	 */
	@Test(timeout = 60000)
	public void enumerateSum2() {
		int dim = 5;
		int size = 2;

		PackedCollection input = new PackedCollection(shape(dim, dim));
		CollectionProducer c = cp(input)
				.enumerate(1, size, 1)
				.sum(2);

		CollectionProducer dy = c.delta(cp(input));
		PackedCollection dout = Process.optimized(dy).get().evaluate();
		dout.print();
	}

	/**
	 * Tests max operation gradient.
	 */
	@Test(timeout = 60000)
	public void max() {
		PackedCollection in = pack(10, 100, 1000);
		CollectionProducer c = cp(in).max();

		c.get().evaluate().print();

		PackedCollection result = c.delta(cp(in)).evaluate();
		result.print();

		for (int i = 0; i < 3; i++) {
			assertEquals(i == 2 ? 1 : 0, result.valueAt(0, i));
		}
	}

	/**
	 * Tests map operation gradient.
	 */
	@Test(timeout = 60000)
	public void map() {
		PackedCollection g = pack(3, 5);
		PackedCollection w = pack(10, 100, 1000);
		CollectionProducer c = cp(g).each()
				.repeat(3).mul(cp(w));

		print(2, 3, c.get().evaluate());

		PackedCollection result = new PackedCollection(shape(2, 3, 3));
		c.delta(p(w)).into(result.traverse(1)).evaluate();
		print(2 * 3, 3, result);

		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals(i == j ? 3 : 0, result.valueAt(0, i, j));
				assertEquals(i == j ? 5 : 0, result.valueAt(1, i, j));
			}
		}
	}

	/**
	 * Tests 1D map operation gradient.
	 */
	@Test(timeout = 60000)
	public void map1d() {
		int count = 1;
		int dim = 2;

		PackedCollection v = pack(2.0)
				.reshape(count, 1).traverse();
		PackedCollection f = pack(4.0, -3.0)
				.reshape(shape(dim));

		Producer cdy = cp(v)
				.multiply(c(3))
				.repeat(2).multiply(cp(f))
				.delta(p(v));
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate();
		log(String.valueOf(dout.getShape()));
		log(String.valueOf(dout.toArrayString()));

		assertEquals(12, dout.toDouble(0));
		assertEquals(-9, dout.toDouble(1));
	}

	/**
	 * Tests 2D map operation gradient.
	 */
	@Test(timeout = 60000)
	public void map2d() {
		int count = 2;
		int dim = 2;

		PackedCollection v = pack(2.0, 3.0, 4.0, 5.0
									, 7.0, 9.0, 8.0, 6.0
									)
				.reshape(count, dim, dim).traverse();
		PackedCollection f = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		CollectionProducer cdy = cp(v)
				.multiply(cp(f))
				.delta(p(v));
		Evaluable<PackedCollection> dy = cdy.get();
		PackedCollection dout = dy.evaluate();
		print(4, 4, dout);

//		for (int i = 0; i < 4; i++) {
//			for (int j = 0; j < 4; j++) {
//				if (i == j) {
//					assertEquals(1.0, dout.toDouble(i * 4 + j));
//				} else {
//					assertEquals(0.0, dout.toDouble(i * 4 + j));
//				}
//			}
//		}
	}

}
