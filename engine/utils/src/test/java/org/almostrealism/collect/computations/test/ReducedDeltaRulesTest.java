/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Verifies the {@link ReduceAxis reduced} deltas that computations derive from their own
 * structure, against the derivative computed on the host and, where it is formed, the
 * contraction of the full Jacobian:
 * <ul>
 *   <li>{@link org.almostrealism.collect.computations.CollectionMaxComputation}, whose
 *       maximum depends only on the element it selects. Each group of the input has a
 *       distinct maximum, so the derivative is defined everywhere it is checked.</li>
 *   <li>Element-wise computations whose operands both depend on the target, which the
 *       chain rule passes on to every operand through the computation's element-wise
 *       partial derivatives.</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class ReducedDeltaRulesTest extends TestSuiteBase {
	/** The number of elements of the target in the element-wise tests. */
	private static final int SIZE = 5;

	/** The number of groups, and the size of each, in the tests of the maximum. */
	private static final int GROUPS = 3, GROUP_SIZE = 4;

	/**
	 * {@code x / (x² + 1)}, whose numerator and denominator both depend on {@code x}, has
	 * the derivative {@code (1 - x²) / (x² + 1)²} at every element, for either axis.
	 */
	@Test(timeout = 60000)
	public void quotientOfDependentOperands() {
		PackedCollection input = new PackedCollection(shape(SIZE)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(SIZE)).randnFill();
		double[] x = input.toArray();
		double[] g = gradient.toArray();

		double[] expected = new double[SIZE];
		for (int i = 0; i < SIZE; i++) {
			double d = x[i] * x[i] + 1;
			expected[i] = g[i] * (1 - x[i] * x[i]) / (d * d);
		}

		CollectionProducer target = cp(input);
		assertAllAxes(target.divide(target.multiply(target).add(c(1.0))), target, cp(gradient), expected);
	}

	/**
	 * {@code x / Σx} divides by a single element broadcast to every position, so the
	 * gradient of each element collects the contribution of every output through the sum:
	 * {@code g_j / S - (Σ_i g_i x_i) / S²}. The values are positive, so the sum is not
	 * close to zero.
	 */
	@Test(timeout = 60000)
	public void quotientByBroadcastSum() {
		PackedCollection input = new PackedCollection(shape(SIZE)).randFill();
		PackedCollection gradient = new PackedCollection(shape(SIZE)).randnFill();
		double[] x = input.toArray();
		double[] g = gradient.toArray();

		double s = Arrays.stream(x).sum();
		double gx = 0;
		for (int i = 0; i < SIZE; i++) {
			gx += g[i] * x[i];
		}

		double[] expected = new double[SIZE];
		for (int j = 0; j < SIZE; j++) {
			expected[j] = g[j] / s - gx / (s * s);
		}

		CollectionProducer target = cp(input);
		CollectionProducer f = target.divide(target.sum());
		assertMatches("out", expected, evaluate(f.delta(target, ReduceAxis.OUT, cp(gradient))));
		assertMatches("jacobian", expected, evaluate(jacobianGradient(f, target, cp(gradient))));
	}

	/**
	 * The sigmoid {@code 1 / (1 + e^-x)} divides a constant by a function of the target,
	 * and has the derivative {@code σ(x)(1 - σ(x))}, also when the target is a matrix.
	 */
	@Test(timeout = 60000)
	public void sigmoid() {
		PackedCollection input = new PackedCollection(shape(2, SIZE)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(2, SIZE)).randnFill();
		double[] x = input.toArray();
		double[] g = gradient.toArray();

		double[] expected = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			double s = 1.0 / (1.0 + Math.exp(-x[i]));
			expected[i] = g[i] * s * (1 - s);
		}

		CollectionProducer target = cp(input);
		assertAllAxes(sigmoid(target), target, cp(gradient), expected);
	}

	/**
	 * A product in which the target appears twice, {@code x · x · 3}, has the derivative
	 * {@code 6x}: the partials of both appearances are summed.
	 */
	@Test(timeout = 60000)
	public void productWithRepeatedOperand() {
		PackedCollection input = new PackedCollection(shape(SIZE)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(SIZE)).randnFill();
		double[] x = input.toArray();
		double[] g = gradient.toArray();

		double[] expected = new double[SIZE];
		for (int i = 0; i < SIZE; i++) {
			expected[i] = g[i] * 6 * x[i];
		}

		CollectionProducer target = cp(input);
		CollectionProducer f = target.multiply(target).multiply(cp(new PackedCollection(shape(SIZE)).fill(3.0)));
		assertAllAxes(f, target, cp(gradient), expected);
	}

	/**
	 * The output-reduced delta passes each group's weight to the position of its maximum,
	 * and nothing to the other positions; with no weight, each maximum is weighted by one.
	 */
	@Test(timeout = 60000)
	public void maxOutputReduced() {
		PackedCollection values = groups();
		PackedCollection gradient = new PackedCollection(shape(GROUPS)).randnFill();
		int[] argmax = maxPositions(values.toArray());
		double[] g = gradient.toArray();

		double[] expected = new double[GROUPS * GROUP_SIZE];
		double[] ones = new double[GROUPS * GROUP_SIZE];
		for (int r = 0; r < GROUPS; r++) {
			expected[r * GROUP_SIZE + argmax[r]] = g[r];
			ones[r * GROUP_SIZE + argmax[r]] = 1.0;
		}

		CollectionProducer input = cp(values);
		CollectionProducer max = max(input.traverse(1));
		assertMatches("weighted", expected, evaluate(max.delta(input, ReduceAxis.OUT, cp(gradient))));
		assertMatches("unweighted", ones, evaluate(max.delta(input, ReduceAxis.OUT)));
	}

	/**
	 * The output-reduced delta of the maximum of a function of the target is the gradient
	 * of the selected element of that function, and agrees with contracting the full
	 * Jacobian of the maximum. The values are positive, so squaring them keeps the
	 * position of each maximum.
	 */
	@Test(timeout = 60000)
	public void maxOutputReducedThroughInput() {
		PackedCollection values = new PackedCollection(shape(GROUPS, GROUP_SIZE)).randFill();
		PackedCollection gradient = new PackedCollection(shape(GROUPS)).randnFill();
		double[] v = values.toArray();
		int[] argmax = maxPositions(v);
		double[] g = gradient.toArray();

		double[] expected = new double[GROUPS * GROUP_SIZE];
		for (int r = 0; r < GROUPS; r++) {
			int i = r * GROUP_SIZE + argmax[r];
			expected[i] = g[r] * 2 * v[i];
		}

		CollectionProducer input = cp(values);
		CollectionProducer max = max(input.multiply(input).traverse(1));
		assertMatches("reduced", expected, evaluate(max.delta(input, ReduceAxis.OUT, cp(gradient))));
		assertMatches("jacobian", expected, evaluate(jacobianGradient(max, input, cp(gradient))));
	}

	/**
	 * The input-reduced delta is the tangent of the element each maximum selects.
	 */
	@Test(timeout = 60000)
	public void maxInputReduced() {
		PackedCollection values = groups();
		PackedCollection tangent = new PackedCollection(shape(GROUPS, GROUP_SIZE)).randnFill();
		int[] argmax = maxPositions(values.toArray());
		double[] t = tangent.toArray();

		double[] expected = new double[GROUPS];
		for (int r = 0; r < GROUPS; r++) {
			expected[r] = t[r * GROUP_SIZE + argmax[r]];
		}

		CollectionProducer input = cp(values);
		CollectionProducer max = max(input.traverse(1));
		assertMatches("tangent", expected, evaluate(max.delta(input, ReduceAxis.IN, cp(tangent))));
	}

	/**
	 * The full Jacobian of each maximum is one at the position it selects and zero
	 * elsewhere, rather than an aggregation of the input's Jacobian.
	 */
	@Test(timeout = 60000)
	public void maxJacobian() {
		PackedCollection values = groups();
		int[] argmax = maxPositions(values.toArray());
		int size = GROUPS * GROUP_SIZE;

		double[] expected = new double[GROUPS * size];
		for (int r = 0; r < GROUPS; r++) {
			expected[r * size + r * GROUP_SIZE + argmax[r]] = 1.0;
		}

		CollectionProducer input = cp(values);
		assertMatches("jacobian", expected, evaluate(max(input.traverse(1)).delta(input)));
	}

	/**
	 * Asserts that the output-reduced and input-reduced deltas of an element-wise
	 * computation, and the contraction of its full Jacobian, all have the expected values.
	 * The Jacobian of an element-wise computation is diagonal, so its vector-Jacobian and
	 * Jacobian-vector products with the same vector are equal.
	 *
	 * @param function the element-wise computation
	 * @param target   the producer to differentiate with respect to
	 * @param vector   the upstream gradient, also used as the tangent
	 * @param expected the expected values
	 */
	private void assertAllAxes(CollectionProducer function, CollectionProducer target,
							   Producer<PackedCollection> vector, double[] expected) {
		assertMatches("out", expected, evaluate(function.delta(target, ReduceAxis.OUT, vector)));
		assertMatches("in", expected, evaluate(function.delta(target, ReduceAxis.IN, vector)));
		assertMatches("jacobian", expected, evaluate(jacobianGradient(function, target, vector)));
	}

	/**
	 * Returns {@link #GROUPS} groups of {@link #GROUP_SIZE} normally distributed values,
	 * whose maxima are distinct with probability one.
	 *
	 * @return the input values
	 */
	private PackedCollection groups() {
		return new PackedCollection(shape(GROUPS, GROUP_SIZE)).randnFill();
	}

	/**
	 * Returns the position of the maximum within each group of values.
	 *
	 * @param values {@link #GROUPS} groups of {@link #GROUP_SIZE} values
	 * @return the position of each group's maximum
	 */
	private static int[] maxPositions(double[] values) {
		int[] argmax = new int[GROUPS];
		for (int r = 0; r < GROUPS; r++) {
			for (int i = 1; i < GROUP_SIZE; i++) {
				if (values[r * GROUP_SIZE + i] > values[r * GROUP_SIZE + argmax[r]]) argmax[r] = i;
			}
		}

		return argmax;
	}

	/**
	 * Optimizes and evaluates a producer.
	 *
	 * @param producer the producer to evaluate
	 * @return the result
	 */
	private PackedCollection evaluate(Producer<PackedCollection> producer) {
		return Process.optimized(producer).get().evaluate();
	}

	/**
	 * Asserts that a result has the expected values.
	 *
	 * @param name     the name of the result, for failure messages
	 * @param expected the expected values
	 * @param actual   the result
	 */
	private void assertMatches(String name, double[] expected, PackedCollection actual) {
		Assert.assertEquals(name + " size", expected.length, actual.getShape().getTotalSize());
		double[] values = actual.toArray(0, expected.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(name + " [" + i + "] of " + Arrays.toString(values),
					expected[i], values[i], 1e-5);
		}

		actual.destroy();
	}
}
