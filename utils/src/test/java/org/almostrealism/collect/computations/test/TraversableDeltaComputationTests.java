/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.GradientTestFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class TraversableDeltaComputationTests implements GradientTestFeatures, TestFeatures {

	@Test
	public void polynomial0() {
		// x + 1
		CollectionProducer<PackedCollection<?>> c = x().add(1);

		// dy = f'(x)
		Evaluable<PackedCollection<?>> dy = c.delta(x()).get();
		PackedCollection<?> out = dy.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.print();

		for (int i = 0; i < 5; i++) {
			assertEquals(1.0, out.toDouble(i));
		}
	}

	@Test
	public void polynomial1() {
		// x^2 + 3x + 1
		CollectionProducer<PackedCollection<?>> c = x().sq().add(x().mul(3)).add(1);

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.print();

		for (int i = 0; i < 5; i++) {
			assertEquals(1.0 + 3 * (i + 1) + (i + 1) * (i + 1), out.toDouble(i));
		}

		// dy = f'(x)
		//    = 2x + 3
		Evaluable<PackedCollection<?>> dy = c.delta(x()).get();
		out = dy.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
		out.print();

		for (int i = 0; i < 5; i++) {
			assertEquals(2 * (i + 1) + 3, out.toDouble(i));
		}
	}

	@Test
	public void polynomial2() {
		int dim = 3;
		int count = 2;

		PackedCollection<?> v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w = pack(4, -3, 2);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// w * x
		CollectionProducer<PackedCollection<?>> c = x.mul(p(w));

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(v);
		System.out.println(Arrays.toString(out.toArray(0, count * dim)));

		// dy = f'(x)
		//    = w
		Evaluable<PackedCollection<?>> dy = c.delta(x).get();
		PackedCollection<?> dout = dy.evaluate(v);
		double d[] = dout.toArray(0, count * dim * dim);
		System.out.println(Arrays.toString(d));

		for (int i = 0; i < count; i++) {
			for (int j = 0 ; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(w.toDouble(j), d[i * dim * dim + j * dim + k]);
					} else {
						assertEquals(0.0, d[i * dim * dim + j * dim + k]);
					}
				}
			}
		}
	}

	@Test
	public void polynomial3() {
		int dim = 3;
		int count = 2;

		PackedCollection<?> v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w = pack(4, -3, 2);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// w * x + 1
		CollectionProducer<PackedCollection<?>> c = x.mul(p(w)).add(c(1).repeat(3).consolidate());

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(v);
		double l[] = out.toArray(0, count * dim);
		System.out.println(Arrays.toString(l));
		assertEquals(1.0, l[0]);
		assertEquals(-2.0, l[1]);

		// dy = f'(x)
		//    = w
		Evaluable<PackedCollection<?>> dy = c.delta(x).get();
		PackedCollection<?> dout = dy.evaluate(v);
		double d[] = dout.toArray(0, count * dim * dim);
		System.out.println(Arrays.toString(d));

		for (int i = 0; i < count; i++) {
			for (int j = 0 ; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(w.toDouble(j), d[i * dim * dim + j * dim + k]);
					} else {
						assertEquals(0.0, d[i * dim * dim + j * dim + k]);
					}
				}
			}
		}
	}

	@Test
	public void polynomial4() {
		int dim = 3;

		PackedCollection<?> v = pack(IntStream.range(0, 4 * dim).boxed()
										.mapToDouble(Double::valueOf).toArray())
										.reshape(4, dim).traverse();
		PackedCollection<?> w = pack(4, -3, 2);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// x^2 + w * x + 1
		CollectionProducer<PackedCollection<?>> c = x.sq().add(x.mul(p(w))).add(c(1).repeat(3).consolidate());

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(v);
		System.out.println(Arrays.toString(out.toArray(0, 4 * dim)));


		HardwareOperator.verboseLog(() -> {
			// dy = f'(x)
			//    = 2x + w
			Evaluable<PackedCollection<?>> dy = c.delta(x).get();
			PackedCollection<?> dout = dy.evaluate(v);
			System.out.println(Arrays.toString(dout.toArray(0, 4 * dim)));
		});
	}

	@Test
	public void power1() {
		int dim = 3;

		PackedCollection<?> v = pack(IntStream.range(0, 4 * dim).boxed()
				.mapToDouble(d -> 1 + d / 2.0).toArray())
				.reshape(4, dim).traverse();
		PackedCollection<?> w = pack(4, 1, 2);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// x^3 + w^x + 1
		CollectionProducer<PackedCollection<?>> c = x.pow(3).add(cp(w).pow(x)).add(c(1).repeat(3).consolidate());

		// y = f(x)
		Evaluable<PackedCollection<?>> y = c.get();
		PackedCollection<?> out = y.evaluate(v);
		out.print();

		for (int n = 0; n < 4; n++) {
			for (int i = 0; i < dim; i++) {
				assertEquals(Math.pow(v.valueAt(n, i), 3) +
								Math.pow(w.valueAt(i), v.valueAt(n, i)) + 1,
						out.toDouble(n * dim + i));
			}
		}

		// dy = f'(x)
		//    = 3x^2 + w^x * log(w)
		Evaluable<PackedCollection<?>> dy = c.delta(x).get();
		PackedCollection<?> dout = dy.evaluate(v);
		dout.print();

		for (int n = 0; n < 4; n++) {
			for (int i = 0; i < dim; i++) {
				for (int j = 0; j < dim; j++) {
					if (i == j) {
						assertEquals(3 * Math.pow(v.valueAt(n, i), 2) +
										Math.pow(w.valueAt(i), v.valueAt(n, i)) * Math.log(w.valueAt(i)),
								dout.valueAt(n, i, j));
					} else {
						assertEquals(0.0, dout.valueAt(n, i, j));
					}
				}
			}
		}
	}

	public OperationProfileNode variance(String name, double x, double y) {
		int c = 2;
		int groups = 1;

		PackedCollection<?> o = new PackedCollection<>(c).fill(x, y);

		return kernelTest(name, () -> {
					CollectionProducer<?> input = cp(o).reshape(-1, groups, c / groups);
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

	@Test
	public void variance1() throws IOException {
		variance("variance1", 1.0, 1.1).save("results/variance1.xml");
	}

	@Test
	public void variance2() throws IOException {
		variance("variance2", 1.5, 2.5).save("results/variance2.xml");
	}

	@Test
	public void divide1() {
		PackedCollection<?> o = new PackedCollection<>(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer<?> input = cp(o).reshape(-1, 1, 2);
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

	@Test
	public void divide2() {
		PackedCollection<?> o = new PackedCollection<>(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer<?> input = cp(o).reshape(-1, 1, 2);
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

	@Test
	public void divide3() {
		PackedCollection<?> o = new PackedCollection<>(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer<?> input = cp(o).reshape(-1, 1, 2);
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

	@Test
	public void divide4() {
		PackedCollection<?> o = new PackedCollection<>(2).fill(1.0, 1.01);
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

	@Test
	public void divide5() {
		PackedCollection<?> o = new PackedCollection<>(2).fill(1.0, 1.01);
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
				});
	}

	@Test
	public void divide6() {
		PackedCollection<?> o = new PackedCollection<>(2).fill(1.5, 2.5);

		kernelTest(() -> {
					CollectionProducer<?> input = cp(o).reshape(-1, 1, 2);
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
				});
	}

	protected void recursiveDivisionTest(Factor<PackedCollection<?>> supply,
										 BiConsumer<PackedCollection<?>, PackedCollection<?>> validate) {
		double x = 1.0;
		double y = 1.02 * Math.pow(2, 5);

		PackedCollection<?> o = new PackedCollection<>(2);

		for (int i = 0; i < 6; i++) {
			y = y / 2.0;
			o.fill(x, y);

			log("Iteration " + i + " y = " + y);
			kernelTest(
					() -> supply.getResultant(cp(o)),
					out -> validate.accept(o, out));
		}
	}

	@Test
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

	@Test
	public void divide8() {
		PackedCollection<?> b = new PackedCollection<>(2);

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

	@Test
	public void divide9() {
//		PackedCollection<?> o = new PackedCollection<>(2).fill(1.0, 1.01);
		PackedCollection<?> o = new PackedCollection<>(2).fill(1.0, 10);
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

	@Test
	public void divide10() {
		double eps = 1e-5;

		recursiveDivisionTest(in -> {
					CollectionProducer input = c(in).reshape(-1, 1, 2);
					CollectionProducer out = input.subtractMean()
							// .divide(mean(pow(subtractMean(input), c(2.0))).add(c(eps)).sqrt())
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

	@Test
	public void divide11() {
		double eps = 1e-5;

		PackedCollection<?> b = new PackedCollection<>(2);

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
				});
	}

	@Test
	public void divideProduct1() {
		int c = 2;

		PackedCollection<?> o = new PackedCollection<>(c).fill(() -> Math.random() / 10.0);
		PackedCollection<?> g = new PackedCollection<>(c).fill(() -> Math.random() / 4.0);
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

					PackedCollection<?> normalized =
							cp(o).subtract(c(muG))
									.divide(c(stdG))
										.evaluate();

					double gradientMean = g.doubleStream().sum() / c;
					PackedCollection<?> gradientByInput = cp(g).multiply(cp(normalized)).evaluate();

					double gradientByInputMean = gradientByInput.doubleStream().sum() / c;
					PackedCollection<?> dLdXGroup = dlDxGroup(
							g, gradientMean, normalized, gradientByInputMean);

					for (int i = 0; i < c; i++) {
						double expected = dLdXGroup.valueAt(i) / stdG;
						double actual = output.valueAt(i);
						log(expected + " vs " + actual);

						Assert.assertEquals(expected, actual, 1e-5);
					}
				});
	}

	@Test
	public void divideProduct2() throws IOException {
		int c = 2;

		PackedCollection<?> o = new PackedCollection<>(c).fill(() -> Math.random() / 10.0);
		PackedCollection<?> g = new PackedCollection<>(c).fill(() -> Math.random() / 4.0);
		PackedCollection<?> b = new PackedCollection<>(c).fill(0.0);
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

					PackedCollection<?> normalized =
							cp(o).subtract(c(muG))
									.divide(c(stdG))
									.evaluate();

					double gradientMean = g.doubleStream().sum() / c;
					PackedCollection<?> gradientByInput = cp(g).multiply(cp(normalized)).evaluate();

					double gradientByInputMean = gradientByInput.doubleStream().sum() / c;
					PackedCollection<?> dLdXGroup = dlDxGroup(
							g, gradientMean, normalized, gradientByInputMean);

					for (int i = 0; i < c; i++) {
						double expected = dLdXGroup.valueAt(i) / stdG;
						double actual = output.valueAt(i);
						log(expected + " vs " + actual);

						Assert.assertEquals(expected, actual, 1e-5);
					}
				}).save("results/divideProduct2.xml");
	}

	@Test
	public void divideProduct3() throws IOException {
		int c = 2;
		divideProduct("divideProduct3", c, () -> new PackedCollection<>(c).fill(() -> Math.random() / 10.0));
	}

	@Test
	public void divideProduct4() throws IOException {
		int c = 2;
		divideProduct("divideProduct4", c, () -> new PackedCollection<>(c).fill(1.0, 1.01));
	}

	public void divideProduct(String name, int c, Supplier<PackedCollection<?>> source) throws IOException {
		PackedCollection<?> o = source.get();
		PackedCollection<?> g = new PackedCollection<>(c).fill(() -> 1 + (Math.random() * 4.0));
		PackedCollection<?> w = new PackedCollection<>(c).fill(1.0);
		PackedCollection<?> b = new PackedCollection<>(c).fill(0.0);
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

					PackedCollection<?> result = normBackwards(o, g, null, null);

					for (int i = 0; i < c; i++) {
						double expected = result.valueAt(i);
						double actual = output.valueAt(i);
						log(expected + " vs " + actual);

						assertSimilar(expected, actual);
					}
				}, false, false, true).save("results/" + name + ".xml");
	}

	@Test
	public void enumerate() {
		int count = 1;
		int dim = 2;

		PackedCollection<?> v = pack(2.0, 3.0, 2.0, 3.0)
				.reshape(count, 1, dim, dim).traverse();

		CollectionProducer cdy = cp(v)
				.reshape(count, dim * dim)
				.enumerate(1, 1)
				.delta(p(v));
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
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

	@Test
	public void embedded1() {
		int dim = 3;
		int count = 2;

		PackedCollection<?> v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w1 = pack(4, -3, 2);
		PackedCollection<?> w2 = pack(2, 1, 5);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// w2 * w1 * x
		CollectionProducer<PackedCollection<?>> c = x.mul(p(w1)).mul(p(w2));

		// dy = f'(x)
		//    = w2 * w1
		Evaluable<PackedCollection<?>> dy = c.delta(x).get();
		PackedCollection<?> dout = dy.evaluate(v);
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

	@Test
	public void embedded2() {
		int dim = 3;

		PackedCollection<?> w1 = pack(4, -3, 2);
		CollectionProducer<PackedCollection<?>> x = cp(pack(0.0, 0.0, 0.0));

		// w0 * x0 + w1 * x1 + w2 * x2
		CollectionProducer<PackedCollection<?>> c = x.mul(p(w1)).sum();

		// x.mul(p(w1)).delta(x).traverse(1).sum().evaluate().print();

		// dy = f'(x)
		//    = w0, w1, w2
		Evaluable<PackedCollection<?>> dy = c.delta(x).get();
		PackedCollection<?> dout = dy.evaluate();
		dout.print();

		for (int i = 0; i < dim; i++) {
			assertEquals(w1.toDouble(i), dout.toDouble(i));
		}
	}

	@Test
	public void repeatMultiply1() {
		int dim = 2;

		PackedCollection<?> matrix = pack(2.0, 3.0, 4.0, 5.0).reshape(dim, dim);
		PackedCollection<?> vector = pack(4.0, -3.0).reshape(shape(dim));

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> c = multiply(traverseEach(cp(matrix)), traverseEach(repeat(dim, cp(vector))));
			PackedCollection<?> out = c.delta(cp(vector)).evaluate();
			System.out.println(out.getShape().toStringDetail());
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

	@Test
	public void repeatMultiply2() {
		int dim = 2;

		PackedCollection<?> matrix = pack(2.0, 3.0, 4.0, 5.0).reshape(dim, dim);
		PackedCollection<?> vector = pack(4.0, -3.0).reshape(shape(dim));

		CollectionProducer<PackedCollection<?>> c = multiply(traverseEach(cp(matrix)), traverseEach(repeat(dim, x(dim))));
		PackedCollection<?> out = c.delta(x(dim)).evaluate(vector);
		System.out.println(out.getShape().toStringDetail());
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

	@Test
	public void repeatMultiply3() {
		int dim = 2;

		PackedCollection<?> matrix = pack(2.0, 3.0, 4.0, 5.0).reshape(dim, dim);
		PackedCollection<?> vector = pack(4.0, -3.0).reshape(shape(1, dim));

		CollectionProducer<PackedCollection<?>> c = multiply(traverseEach(cp(matrix)), traverseEach(repeat(dim, x(dim))));
		PackedCollection<?> out = c.delta(cp(matrix)).evaluate(vector.traverse());
		System.out.println(out.getShape().toStringDetail());
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

	@Test
	public void multiply() {
		int dim = 4;

		PackedCollection<?> v = new PackedCollection<>(shape(dim)).randFill();
		PackedCollection<?> f = new PackedCollection<>(shape(dim)).randFill();

		CollectionProducer cdy = cp(v).multiply(p(f))
				.delta(p(v));
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate().reshape(dim, dim);
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

	@Test
	public void multiplySum() {
		int dim = 4;

		PackedCollection<?> v = new PackedCollection<>(shape(dim)).randFill();
		PackedCollection<?> f = new PackedCollection<>(shape(dim)).randFill();

		CollectionProducer cdy = cp(v).multiply(p(f))
				.delta(p(v))
				.sum(1);
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
		System.out.println(dout.getShape().toStringDetail());
		dout.print();

		for (int n = 0; n < dim; n++) {
			assertEquals(f.toDouble(n), dout.toDouble(n));
		}
	}

	@Test
	public void multiplyEnumerate() {
		int dim = 2;

		PackedCollection<?> v = pack(2.0, 3.0, 2.0, 3.0)
				.reshape(dim, dim);
		PackedCollection<?> f = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		CollectionProducer cdy = cp(v).multiply(p(f))
				.enumerate(1, 1)
				.delta(p(v));
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
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

	@Test
	public void enumerateIndex() {
		int dim = 2;

		PackedCollection<?> v = pack(2.0, 3.0, 2.0, 3.0)
				.reshape(dim, dim);
		PackedCollection<?> f = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		CollectionProducer cdy = cp(v).multiply(p(f)).enumerate(1, 1);
		// cdy = ((IndexProjectionProducerComputation) cdy).getIndex();
		cdy = cdy.delta(p(v));

		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
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

	@Test
	public void enumerateMultiplySum() {
		boolean enableSum = true;
		int count = 1;
		int dim = 2;

		PackedCollection<?> v = pack(2.0, 3.0, 2.0, 3.0)
				.reshape(count, 1, dim, dim).traverse();
		PackedCollection<?> f = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		CollectionProducer cdy = cp(v)
				.reshape(count, dim * dim)
				.enumerate(1, 1)
				.delta(p(v))
				.reshape(dim * dim, dim * dim)
				.traverse(1)
				.multiply(cp(f).reshape(dim * dim).traverse(1).expand(dim * dim));
		if (enableSum) cdy = cdy.sum(1);
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
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

	@Test
	public void enumerateMap() {
		boolean enableOptimize = true;
		boolean enableSum = true;
		int count = 1;
		int dim = 2;

		ParallelProcess.explicitIsolationTargets.add(operationFilter("Enumerate"));

		try {
			PackedCollection<?> v = pack(2.0, 3.0, 2.0, 3.0)
					.reshape(count, 1, dim, dim).traverse();
			PackedCollection<?> f = pack(4.0, -3.0, 2.0, 1.5)
					.reshape(shape(dim, dim));

			CollectionProducer cdy = cp(v)
					.reshape(count, dim * dim)
					.enumerate(1, 1)
					.map(x -> x.multiply(2.0))
					.delta(p(v))
					.reshape(dim * dim, dim * dim)
					.traverse(1)
					.multiply(cp(f).reshape(dim * dim).traverse(1).expand(dim * dim));
			if (enableSum) cdy = cdy.sum(1);
			if (enableOptimize) cdy = (CollectionProducer) Process.optimized(cdy);
			Evaluable<PackedCollection<?>> dy = cdy.get();
			PackedCollection<?> dout = dy.evaluate();
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

	@Test
	public void multiplyTwice() {
		int dim = 5;

		PackedCollection<?> input = new PackedCollection<>(shape(dim));
		CollectionProducer<PackedCollection<?>> c = cp(input)
				.multiply(3)
				.multiply(2);

		CollectionProducer<PackedCollection<?>> dy = c.delta(cp(input));
//		PackedCollection<?> dout = Process.optimized(dy).get().evaluate();
		PackedCollection<?> dout = dy.get().evaluate();
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
	}

	@Test
	public void sumMultiply() {
		int dim = 2;

		PackedCollection<?> input = new PackedCollection<>(shape(dim, dim));
		CollectionProducer<PackedCollection<?>> c = cp(input)
				.sum(1)
				.multiply(2);

		CollectionProducer<PackedCollection<?>> dy = c.delta(cp(input));
		// PackedCollection<?> dout = Process.optimized(dy).get().evaluate();
		PackedCollection<?> dout = dy.get().evaluate();
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
	}

	@Test
	public void enumerateMultiply() {
		int dim = 5;
		int size = 2;

		PackedCollection<?> input = new PackedCollection<>(shape(dim, dim));
		CollectionProducer<PackedCollection<?>> c = cp(input)
				.enumerate(1, size, 1)
				.multiply(2);

		CollectionProducer<PackedCollection<?>> dy = c.delta(cp(input));
		PackedCollection<?> dout = Process.optimized(dy).get().evaluate();
		assertEquals(80, dout.doubleStream().sum());
	}

	@Test
	public void enumerateSum1() {
		int count = 2;
		int dim = 3;

		PackedCollection<?> v = pack(2.0, 3.0, 4.0,
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
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
		dout.print();

		assertEquals(7.0, dout.toDouble(0));
		assertEquals(9.0, dout.toDouble(1));
		assertEquals(11.0, dout.toDouble(2));
		assertEquals(7.0, dout.toDouble(3));
		assertEquals(9.0, dout.toDouble(4));
		assertEquals(11.0, dout.toDouble(5));
	}

	@Test
	public void enumerateSum2() {
		int dim = 5;
		int size = 2;

		PackedCollection<?> input = new PackedCollection<>(shape(dim, dim));
		CollectionProducer<PackedCollection<?>> c = cp(input)
				.enumerate(1, size, 1)
				.sum(2);

		CollectionProducer<PackedCollection<?>> dy = c.delta(cp(input));
		PackedCollection<?> dout = Process.optimized(dy).get().evaluate();
		dout.print();
	}

	@Test
	public void max() {
		PackedCollection<?> in = pack(10, 100, 1000);
		CollectionProducer<PackedCollection<?>> c = cp(in).max();

		c.get().evaluate().print();

		PackedCollection<?> result = c.delta(cp(in)).evaluate();
		result.print();

		for (int i = 0; i < 3; i++) {
			assertEquals(i == 2 ? 1 : 0, result.valueAt(0, i));
		}
	}

	@Test
	public void map() {
		PackedCollection<?> g = pack(3, 5);
		PackedCollection<?> w = pack(10, 100, 1000);
		CollectionProducer<PackedCollection<?>> c = cp(g).each()
				.map(shape(3), v -> v.repeat(3).mul(cp(w)));

		print(2, 3, c.get().evaluate());

		PackedCollection<?> result = new PackedCollection<>(shape(2, 3, 3));
		c.delta(p(w)).into(result.traverse(1)).evaluate();
		print(2 * 3, 3, result);

		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals(i == j ? 3 : 0, result.valueAt(0, i, j));
				assertEquals(i == j ? 5 : 0, result.valueAt(1, i, j));
			}
		}
	}

	@Test
	public void map1d() {
		int count = 1;
		int dim = 2;

		PackedCollection<?> v = pack(2.0)
				.reshape(count, 1).traverse();
		PackedCollection<?> f = pack(4.0, -3.0)
				.reshape(shape(dim));

		Producer cdy = cp(v)
				.multiply(c(3))
				.map(shape(2), x -> x.repeat(2).multiply(cp(f)))
				.delta(p(v));
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
		System.out.println(dout.getShape());
		System.out.println(dout.toArrayString());

		assertEquals(12, dout.toDouble(0));
		assertEquals(-9, dout.toDouble(1));
	}

	@Test
	public void map2d() {
		int count = 2;
		int dim = 2;

		PackedCollection<?> v = pack(2.0, 3.0, 4.0, 5.0
									, 7.0, 9.0, 8.0, 6.0
									)
				.reshape(count, dim, dim).traverse();
		PackedCollection<?> f = pack(4.0, -3.0, 2.0, 1.5)
				.reshape(shape(dim, dim));

		CollectionProducer cdy = cp(v)
				.map(x -> x.multiply(cp(f)))
				.delta(p(v));
		Evaluable<PackedCollection<?>> dy = cdy.get();
		PackedCollection<?> dout = dy.evaluate();
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

	@Test
	public void enumerate2d() {
		int dim = 6;
		int size = 3;
		int filterCount = 2;
		int pad = size - 1;
		TraversalPolicy outputShape = shape(dim - pad, dim - pad, filterCount);

		PackedCollection<?> input = integers(1, 1 + dim * dim).evaluate().reshape(dim, dim);
		PackedCollection<?> filters = new PackedCollection<>(shape(size, size, filterCount)).fill(Math::random);

		CollectionProducer<PackedCollection<?>> c = cp(input)
				.enumerate(1, size, 1)
				.enumerate(1, size, 1)
				.traverse(2)
				.repeat(filterCount)
				.traverse(2)
				.multiply(cp(filters)
						.repeat(outputShape.length(1)).traverse(0)
						.repeat(outputShape.length(0)).traverse(2))
				.traverse();

		PackedCollection<?> result = Process.optimized(c.delta(p(input))).get().evaluate();
		result.print();
	}

	@Test
	public void conv2d() {
		int size = 3;
		int filterCount = 8;

		PackedCollection<?> input = integers(1, 101).evaluate().reshape(10, 10);
		PackedCollection<?> filters = pack(1, 2, 3, 4, 5, 6, 7, 8);

		CollectionProducer<PackedCollection<?>> c = cp(input)
						.enumerate(1, size, 1)
						.enumerate(1, size, 1)
						.traverse(2)
						.expand(filterCount, v -> v.repeat(filterCount).each().multiply(p(filters)))
						.traverse()
						.reduce(v -> v.sum());

		PackedCollection<?> result = c.delta(p(filters)).evaluate();
		print(50, 8, result);
		// TODO  assertions
	}

	@Test
	public void conv2dEnumerateProduct() {
		int h = 3; // 10;
		int w = 4; // 10;
		int size = 3;
		int filterCount = 2; // 8;

		PackedCollection<?> input = integers(1, (h * w) + 1).evaluate().reshape(h, w);
		PackedCollection<?> filters = integers(1, filterCount + 1).evaluate();

		CollectionProducer<PackedCollection<?>> c = cp(input)
				.enumerate(1, size, 1)
				.enumerate(1, size, 1)
				.traverse(2)
				.expand(filterCount, v -> v.repeat(filterCount).each().multiply(p(filters)))
				.traverse()
				.reduce(v -> v.sum());

		int outSize = shape(c).getTotalSize();
		PackedCollection<?> g = integers(1, outSize + 1).evaluate().reshape(shape(c));
		Producer<PackedCollection<?>> weightFlat = reshape(shape(filterCount), p(filters));

		Producer<PackedCollection<?>> cdy = c.delta(p(filters))
				.reshape(outSize, filterCount)
				.traverse(1)
				.multiply(c(g).reshape(outSize).traverse(1).expand(filterCount))
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(filterCount))
				.each();

		PackedCollection<?> sparse = new PackedCollection<>(shape(outSize, filterCount));

		c.delta(p(filters)).into(sparse.traverse()).evaluate();
		print(h, filterCount, sparse);

		c.delta(p(filters))
				.reshape(outSize, filterCount)
				.traverse(1)
				.multiply(c(g).reshape(outSize).traverse(1).expand(filterCount))
				.enumerate(1, 1)
				.into(sparse.each()).evaluate();
		print(h, filterCount, sparse);

		Supplier<Runnable> cda = a(each(weightFlat), subtract(each(weightFlat), multiply(c(2.0), cdy)));
		cda.get().run();
	}
}
