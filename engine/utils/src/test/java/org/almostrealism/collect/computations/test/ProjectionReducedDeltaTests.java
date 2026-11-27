/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations.test;

import io.almostrealism.compute.Process;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicIndexProjectionProducerComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Tests the reduced deltas of index projections: the vector-Jacobian product
 * ({@link ReduceAxis#OUT}) of a permutation is the inverse permutation of the weight, and
 * the Jacobian-vector product ({@link ReduceAxis#IN}) of any projection is the projection
 * of the tangent, so neither forms a Jacobian. Each delta is checked against values
 * computed on the host, and its graph is checked to hold no computation larger than the
 * operand.
 */
public class ProjectionReducedDeltaTests extends TestSuiteBase {

	/** Rows of the matrices transposed. */
	private static final int ROWS = 3;

	/** Columns of the matrices transposed. */
	private static final int COLS = 4;

	/** The OUT delta of {@code transpose()} is the transpose of the weight. */
	@Test(timeout = 60000)
	public void transposeOutIsTransposedWeight() {
		PackedCollection input = new PackedCollection(shape(ROWS, COLS)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(COLS, ROWS)).randnFill();
		CollectionProducer target = cp(input);

		CollectionProducer delta = target.transpose().delta(target, ReduceAxis.OUT, cp(gradient));
		assertReduced(delta, ROWS * COLS);
		assertMatches(transposed(gradient.toArray(), COLS, ROWS), evaluate(delta));
	}

	/** The IN delta of {@code transpose()} is the transpose of the tangent. */
	@Test(timeout = 60000)
	public void transposeInIsTransposedTangent() {
		PackedCollection input = new PackedCollection(shape(ROWS, COLS)).randnFill();
		PackedCollection tangent = new PackedCollection(shape(ROWS, COLS)).randnFill();
		CollectionProducer target = cp(input);

		CollectionProducer delta = target.transpose().delta(target, ReduceAxis.IN, cp(tangent));
		assertReduced(delta, ROWS * COLS);
		assertMatches(transposed(tangent.toArray(), ROWS, COLS), evaluate(delta));
	}

	/**
	 * A batch of row vectors transposed as {@code traverse(0).transpose()}, the operand a
	 * sparse product sees, carries its gradient back as the transpose of the weight.
	 */
	@Test(timeout = 60000)
	public void batchOfRowVectorsCarriesGradientBack() {
		int batch = 2;
		int cols = 6;
		PackedCollection input = new PackedCollection(shape(batch, cols)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(cols, batch)).randnFill();
		CollectionProducer target = cp(input);

		CollectionProducer delta = target.traverse(0).transpose()
				.delta(target, ReduceAxis.OUT, cp(gradient));
		assertReduced(delta, batch * cols);
		assertMatches(transposed(gradient.toArray(), cols, batch), evaluate(delta));
	}

	/** The reduction passes on through the input: {@code (2x)ᵀ} has the OUT delta {@code 2gᵀ}. */
	@Test(timeout = 60000)
	public void transposeOfAComputationChainsTheReduction() {
		PackedCollection input = new PackedCollection(shape(ROWS, COLS)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(COLS, ROWS)).randnFill();
		CollectionProducer target = cp(input);

		CollectionProducer delta = target.multiply(2.0).transpose()
				.delta(target, ReduceAxis.OUT, cp(gradient));
		assertReduced(delta, ROWS * COLS);

		double[] expected = transposed(gradient.toArray(), COLS, ROWS);
		for (int i = 0; i < expected.length; i++) expected[i] *= 2;
		assertMatches(expected, evaluate(delta));
	}

	/** {@code permute(2, 0, 1)} has the inverse permutation as its OUT delta and itself as its IN delta. */
	@Test(timeout = 60000)
	public void permuteHasReducedDeltasForBothAxes() {
		int a = 2;
		int b = 3;
		int c = 4;
		PackedCollection input = new PackedCollection(shape(a, b, c)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(c, a, b)).randnFill();
		PackedCollection tangent = new PackedCollection(shape(a, b, c)).randnFill();
		CollectionProducer target = cp(input);
		CollectionProducer permuted = target.permute(2, 0, 1);

		CollectionProducer out = permuted.delta(target, ReduceAxis.OUT, cp(gradient));
		CollectionProducer in = permuted.delta(target, ReduceAxis.IN, cp(tangent));
		assertReduced(out, a * b * c);
		assertReduced(in, a * b * c);

		double[] g = gradient.toArray();
		double[] t = tangent.toArray();
		double[] expectedOut = new double[a * b * c];
		double[] expectedIn = new double[a * b * c];
		for (int i = 0; i < a; i++) {
			for (int j = 0; j < b; j++) {
				for (int k = 0; k < c; k++) {
					int inIndex = (i * b + j) * c + k;
					int outIndex = (k * a + i) * b + j;
					expectedOut[inIndex] = g[outIndex];
					expectedIn[outIndex] = t[inIndex];
				}
			}
		}

		assertMatches(expectedOut, evaluate(out));
		assertMatches(expectedIn, evaluate(in));
	}

	/** A projection that is not a permutation still has the projected tangent as its IN delta. */
	@Test(timeout = 60000)
	public void subsetInIsSubsetOfTangent() {
		PackedCollection input = new PackedCollection(shape(5)).randnFill();
		PackedCollection tangent = new PackedCollection(shape(5)).randnFill();
		CollectionProducer target = cp(input);

		CollectionProducer delta = target.subset(shape(2), 1).delta(target, ReduceAxis.IN, cp(tangent));
		assertReduced(delta, 5);

		double[] t = tangent.toArray();
		assertMatches(new double[] { t[1], t[2] }, evaluate(delta));
	}

	/** A projection that is not a permutation still gives the right OUT delta, through the Jacobian. */
	@Test(timeout = 60000)
	public void subsetOutStillMatches() {
		PackedCollection input = new PackedCollection(shape(5)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(2)).randnFill();
		CollectionProducer target = cp(input);

		CollectionProducer delta = target.subset(shape(2), 1).delta(target, ReduceAxis.OUT, cp(gradient));
		double[] g = gradient.toArray();
		assertMatches(new double[] { 0, g[0], g[1], 0, 0 }, evaluate(delta));
	}

	/**
	 * A dynamic projection whose index input depends on the target, the selection of each
	 * row's maximum, differentiates only the gathered values: its IN delta is the tangent
	 * at each row's maximum, with no Jacobian formed, and its OUT delta places the weight
	 * at that position.
	 */
	@Test(timeout = 60000)
	public void dynamicSelectionByDependentIndex() {
		PackedCollection input = new PackedCollection(shape(ROWS, COLS)).randnFill();
		PackedCollection tangent = new PackedCollection(shape(ROWS, COLS)).randnFill();
		PackedCollection gradient = new PackedCollection(shape(ROWS, 1)).randnFill();
		CollectionProducer target = cp(input);
		CollectionProducer rows = target.traverse(1);
		CollectionProducer selected = new DynamicIndexProjectionProducerComputation("projectMax",
				shape(rows).replace(shape(1)), (args, idx) -> args[2].getValueAt(idx).toInt(),
				true, rows, indexOfMax(rows));

		CollectionProducer in = selected.delta(target, ReduceAxis.IN, cp(tangent));
		CollectionProducer out = selected.delta(target, ReduceAxis.OUT, cp(gradient));
		assertReduced(in, ROWS * COLS);

		double[] x = input.toArray();
		double[] t = tangent.toArray();
		double[] g = gradient.toArray();
		double[] expectedIn = new double[ROWS];
		double[] expectedOut = new double[ROWS * COLS];
		for (int r = 0; r < ROWS; r++) {
			int max = 0;
			for (int c = 1; c < COLS; c++) {
				if (x[r * COLS + c] > x[r * COLS + max]) max = c;
			}

			expectedIn[r] = t[r * COLS + max];
			expectedOut[r * COLS + max] = g[r];
		}

		assertMatches(expectedIn, evaluate(in));
		assertMatches(expectedOut, evaluate(out));
	}

	/**
	 * Returns the transpose of a row-major {@code rows × cols} matrix.
	 *
	 * @param values the matrix
	 * @param rows   its rows
	 * @param cols   its columns
	 * @return the {@code cols × rows} transpose, row-major
	 */
	private static double[] transposed(double[] values, int rows, int cols) {
		double[] result = new double[values.length];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				result[c * rows + r] = values[r * cols + c];
			}
		}

		return result;
	}

	/**
	 * Evaluates a delta to host values.
	 *
	 * @param delta the delta
	 * @return its values
	 */
	private double[] evaluate(CollectionProducer delta) {
		PackedCollection result = delta.evaluate();
		return result.toArray(0, result.getShape().getTotalSize());
	}

	/**
	 * Asserts that two arrays agree element by element.
	 *
	 * @param expected the values computed on the host
	 * @param actual   the values of the delta
	 */
	private void assertMatches(double[] expected, double[] actual) {
		assertEquals("length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals("element " + i, expected[i], actual[i]);
		}
	}

	/**
	 * Asserts that no computation in the graph of {@code delta} is larger than {@code bound}
	 * elements, so no Jacobian was formed.
	 *
	 * @param delta the delta
	 * @param bound the largest output allowed
	 */
	private void assertReduced(CollectionProducer delta, long bound) {
		long largest = largestComputation(delta, Collections.newSetFromMap(new IdentityHashMap<>()));
		assertTrue("largest=" + largest + " bound=" + bound, largest <= bound);
	}

	/**
	 * Returns the largest output of any computation in the graph, other than a provider of
	 * existing memory.
	 *
	 * @param node    the node to start from
	 * @param visited the nodes already visited
	 * @return the largest total size of any computed collection in the graph
	 */
	private static long largestComputation(Object node, Set<Object> visited) {
		if (node == null || !visited.add(node)) return 0;

		long largest = node instanceof CollectionProducer && !((CollectionProducer) node).isProvider() ?
				((CollectionProducer) node).getShape().getTotalSizeLong() : 0;

		if (node instanceof Process) {
			for (Object child : ((Process<?, ?>) node).getChildren()) {
				largest = Math.max(largest, largestComputation(child, visited));
			}
		}

		return largest;
	}
}
