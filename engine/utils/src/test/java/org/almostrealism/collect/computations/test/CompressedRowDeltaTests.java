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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.Process;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.SegmentedOuterProductComputation;
import org.almostrealism.algebra.computations.SegmentedWeightedSumComputation;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Tests the reduced deltas of the product of a sparse matrix, read through a
 * {@link CompressedRowTraversalOrdering}, with a vector or a matrix: the gradient with respect to
 * the input is the transposed product {@code Aᵀ·g}, and the gradient with respect to the stored
 * entries is {@code g[row(k)] · x[column(k)]} for each entry {@code k}. Each is checked against
 * the dense reference and against central differences of the scalar loss {@code Σ g ⊙ (A·x)}, on
 * every available backend, and neither may form a Jacobian.
 *
 * <p>The fixed matrix is the {@code (4, 6)} matrix</p>
 * <pre>
 * [[-1,  0, 0,  2,  0, 0],
 *  [ 0,  0, 0,  0,  0, 0],
 *  [ 3, -4, 5,  6, -7, 0],
 *  [ 0,  0, 8,  0,  0, 0]]
 * </pre>
 * <p>stored as eight entries, with an empty row, an empty last column, a full row and a row whose
 * entries are stored out of column order. A second matrix has a long row and a long column.</p>
 */
public class CompressedRowDeltaTests extends TestSuiteBase implements MatrixFeatures, BackendTestFeatures {
	/** The row offsets of the fixed matrix. */
	private static final int[] OFFSETS = { 0, 2, 2, 7, 8 };

	/** The column of each entry of the fixed matrix. */
	private static final int[] COLUMNS = { 3, 0, 0, 1, 2, 3, 4, 2 };

	/** The step of the central differences; the loss is linear in each operand, so it is exact. */
	private static final double STEP = 0.5;

	/**
	 * A sparse matrix given by its host index structure and entries, with the dense reference
	 * computations its deltas are checked against.
	 */
	private class Sparse {
		/** The number of rows. */
		private final int rows;

		/** The number of columns. */
		private final int cols;

		/** The row offsets. */
		private final int[] offsets;

		/** The column of each entry. */
		private final int[] columns;

		/** The ordering of the matrix. */
		private final CompressedRowTraversalOrdering order;

		/**
		 * Creates the matrix.
		 *
		 * @param cols    the number of columns
		 * @param offsets the row offsets
		 * @param columns the column of each entry
		 */
		Sparse(int cols, int[] offsets, int[] columns) {
			this.rows = offsets.length - 1;
			this.cols = cols;
			this.offsets = offsets;
			this.columns = columns;
			this.order = CompressedRowTraversalOrdering.of(cols, offsets, columns);
		}

		/**
		 * Returns the matrix whose entries are the given values, read through the ordering.
		 *
		 * @param values the entries in physical order
		 * @return the sparse matrix
		 */
		PackedCollection matrix(PackedCollection values) {
			return new PackedCollection(shape(rows, cols), 0, values, 0, order);
		}

		/**
		 * Returns the row of each entry.
		 *
		 * @return the row of the entry at each physical position
		 */
		int[] entryRows() {
			int[] result = new int[columns.length];
			for (int i = 0; i < rows; i++) {
				for (int k = offsets[i]; k < offsets[i + 1]; k++) result[k] = i;
			}

			return result;
		}

		/**
		 * Returns {@code A·x} for {@code width} columns of {@code x}, as the dense reference.
		 *
		 * @param values the entries
		 * @param x      the input, {@code (cols, width)} row-major
		 * @param width  the number of columns of the input
		 * @return the product, {@code (rows, width)} row-major
		 */
		double[] product(double[] values, double[] x, int width) {
			double[] result = new double[rows * width];
			int[] entryRows = entryRows();
			for (int k = 0; k < columns.length; k++) {
				for (int w = 0; w < width; w++) {
					result[entryRows[k] * width + w] += values[k] * x[columns[k] * width + w];
				}
			}

			return result;
		}

		/**
		 * Returns {@code Aᵀ·g}, the dense reference of the input gradient.
		 *
		 * @param values the entries
		 * @param g      the upstream gradient, {@code (rows, width)} row-major
		 * @param width  the number of columns
		 * @return the gradient, {@code (cols, width)} row-major
		 */
		double[] transposedProduct(double[] values, double[] g, int width) {
			double[] result = new double[cols * width];
			int[] entryRows = entryRows();
			for (int k = 0; k < columns.length; k++) {
				for (int w = 0; w < width; w++) {
					result[columns[k] * width + w] += values[k] * g[entryRows[k] * width + w];
				}
			}

			return result;
		}

		/**
		 * Returns the dense weight gradient {@code g ⊗ x} restricted to the stored entries.
		 *
		 * @param g     the upstream gradient, {@code (rows, width)} row-major
		 * @param x     the input, {@code (cols, width)} row-major
		 * @param width the number of columns
		 * @return the gradient of each entry
		 */
		double[] entryGradient(double[] g, double[] x, int width) {
			double[] result = new double[columns.length];
			int[] entryRows = entryRows();
			for (int k = 0; k < columns.length; k++) {
				for (int w = 0; w < width; w++) {
					result[k] += g[entryRows[k] * width + w] * x[columns[k] * width + w];
				}
			}

			return result;
		}
	}

	/**
	 * Returns the fixed matrix.
	 *
	 * @return the {@code (4, 6)} matrix
	 */
	private Sparse fixed() {
		return new Sparse(6, OFFSETS, COLUMNS);
	}

	/**
	 * Returns a {@code (7, 9)} matrix whose first row is full, stored in descending column order,
	 * whose first column is full, and which has an empty row and an empty column.
	 *
	 * @return the matrix
	 */
	private Sparse longRowAndColumn() {
		int[] offsets = { 0, 9, 10, 12, 12, 14, 15, 17 };
		int[] columns = { 8, 7, 6, 5, 4, 3, 2, 1, 0,
				0,
				3, 0,
				0, 7,
				0,
				1, 0 };
		return new Sparse(9, offsets, columns);
	}

	/**
	 * Returns the values {@code (i % modulus) - modulus / 2} for {@code i} in
	 * {@code [start, start + n)}, computed on the device.
	 *
	 * @param n       the number of values
	 * @param start   the first index
	 * @param modulus the modulus
	 * @return the values
	 */
	private PackedCollection pattern(int n, int start, int modulus) {
		return integers(start, start + n).mod(c(modulus)).subtract(c(modulus / 2)).evaluate();
	}

	/**
	 * Returns the given values with the one at the given index moved by the given step, computed
	 * on the device.
	 *
	 * @param values the values
	 * @param index  the index of the value to move
	 * @param step   the amount to move it by
	 * @return the moved values
	 */
	private PackedCollection perturbed(PackedCollection values, int index, double step) {
		int n = values.getShape().getTotalSize();
		return cp(values).add(equals(integers(0, n), c(index)).multiply(c(step))).evaluate();
	}

	/**
	 * Returns the host copy of the given values, for the dense reference computations.
	 *
	 * @param values the values
	 * @return their elements
	 */
	private static double[] host(PackedCollection values) {
		return values.toArray(0, values.getShape().getTotalSize());
	}

	/**
	 * Returns the shape of an operand with the given number of rows and columns: a vector for one
	 * column, a matrix otherwise.
	 *
	 * @param rows  the number of rows
	 * @param width the number of columns
	 * @return the shape
	 */
	private TraversalPolicy operandShape(int rows, int width) {
		return width == 1 ? shape(rows) : shape(rows, width);
	}

	/**
	 * Returns {@code Σ g ⊙ (A·x)} evaluated on the given backend.
	 *
	 * @param sparse  the matrix
	 * @param values  the entries
	 * @param x       the input
	 * @param g       the upstream gradient
	 * @param width   the number of columns
	 * @param backend the backend
	 * @return the loss
	 */
	private double loss(Sparse sparse, PackedCollection values, PackedCollection x, double[] g, int width,
						ComputeRequirement backend) {
		PackedCollection matrix = sparse.matrix(values);
		PackedCollection y = evaluate(matmul(cp(matrix), cp(x.reshape(operandShape(sparse.cols, width)))), backend);
		double[] result = y.toArray(0, sparse.rows * width);
		double total = 0.0;
		for (int i = 0; i < result.length; i++) total += g[i] * result[i];
		return total;
	}

	/**
	 * Returns the central differences of the loss with respect to each element of the input, or,
	 * when {@code entries} is true, of each stored entry.
	 *
	 * @param sparse  the matrix
	 * @param values  the entries
	 * @param x       the input
	 * @param g       the upstream gradient
	 * @param width   the number of columns
	 * @param entries true to differentiate by the entries, false by the input
	 * @param backend the backend
	 * @return the central differences
	 */
	private double[] differences(Sparse sparse, PackedCollection values, PackedCollection x, double[] g, int width,
								 boolean entries, ComputeRequirement backend) {
		PackedCollection varied = entries ? values : x;
		double[] result = new double[varied.getShape().getTotalSize()];
		for (int i = 0; i < result.length; i++) {
			PackedCollection up = perturbed(varied, i, STEP);
			PackedCollection down = perturbed(varied, i, -STEP);
			double upper = entries ? loss(sparse, up, x, g, width, backend) : loss(sparse, values, up, g, width, backend);
			double lower = entries ? loss(sparse, down, x, g, width, backend) : loss(sparse, values, down, g, width, backend);
			result[i] = (upper - lower) / (2 * STEP);
		}

		return result;
	}

	/**
	 * Asserts that the given values agree element by element.
	 *
	 * @param label    the label of the comparison
	 * @param expected the expected values
	 * @param actual   the actual values
	 */
	private void assertValues(String label, double[] expected, double[] actual) {
		assertEquals(label + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(label + " element " + i, expected[i], actual[i], 1e-3 * Math.max(1.0, Math.abs(expected[i])));
		}
	}

	/**
	 * Asserts that no computation in the graph of the given delta has an output larger than the
	 * given bound, so that no Jacobian is formed, and that the graph contains a computation of the
	 * given type.
	 *
	 * @param delta the delta
	 * @param bound the largest output allowed
	 * @param type  the computation the delta must be built from
	 */
	private void assertReduced(CollectionProducer delta, long bound, Class<?> type) {
		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		long largest = largestComputation(delta, visited);
		assertTrue("largest=" + largest + " bound=" + bound, largest <= bound);
		assertTrue("the delta should contain a " + type.getSimpleName(),
				visited.stream().anyMatch(type::isInstance));
	}

	/**
	 * Returns the largest output of any computation, other than a provider of existing memory, in
	 * the graph of the given producer.
	 *
	 * @param node    the producer or process to inspect
	 * @param visited the nodes already inspected
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

	/**
	 * Asserts, on every available backend, that both output-reduced deltas of the product of the
	 * given matrix with an input of the given width equal the dense reference and central
	 * differences of the loss, and that neither forms a Jacobian.
	 *
	 * @param sparse the matrix
	 * @param width  the number of columns of the input
	 */
	private void assertGradients(Sparse sparse, int width) {
		int count = sparse.columns.length;
		PackedCollection values = pattern(count, 3, 7);
		PackedCollection x = pattern(sparse.cols * width, 1, 5);
		PackedCollection g = pattern(sparse.rows * width, 2, 9);
		long bound = Math.max(count, (long) Math.max(sparse.rows, sparse.cols) * width);

		PackedCollection matrix = sparse.matrix(values);
		CollectionProducer input = cp(x.reshape(operandShape(sparse.cols, width)));
		CollectionProducer entries = cp(matrix);
		CollectionProducer upstream = cp(g.reshape(shape(sparse.rows, width)));

		CollectionProducer inputGradient = matmul(cp(matrix), input).delta(input, ReduceAxis.OUT, upstream);
		CollectionProducer entryGradient = matmul(entries, cp(x.reshape(operandShape(sparse.cols, width))))
				.delta(entries, ReduceAxis.OUT, upstream);
		assertReduced(inputGradient, bound, SegmentedWeightedSumComputation.class);
		assertReduced(entryGradient, bound, SegmentedOuterProductComputation.class);

		double[] expectedInput = sparse.transposedProduct(host(values), host(g), width);
		double[] expectedEntries = sparse.entryGradient(host(g), host(x), width);

		for (ComputeRequirement backend : backends()) {
			PackedCollection actualInput = evaluate(inputGradient, backend);
			assertEquals((long) sparse.cols * width, actualInput.getShape().getTotalSizeLong());
			double[] inputValues = actualInput.toArray(0, sparse.cols * width);
			assertValues("input gradient on " + backend, expectedInput, inputValues);
			assertValues("input central differences on " + backend,
					differences(sparse, values, x, host(g), width, false, backend), inputValues);

			PackedCollection actualEntries = evaluate(entryGradient, backend);
			assertEquals(count, actualEntries.getShape().getTotalSize());
			double[] entry = actualEntries.toArray(0, count);
			assertValues("entry gradient on " + backend, expectedEntries, entry);
			assertValues("entry central differences on " + backend,
					differences(sparse, values, x, host(g), width, true, backend), entry);
		}
	}

	/**
	 * Asserts, on every available backend, that both input-reduced deltas of the product of the
	 * given matrix with an input of the given width are the Jacobian-vector products: the product
	 * of the matrix with a tangent of the input, and the product with the input of the matrix
	 * whose entries are a tangent of the entries.
	 *
	 * @param sparse the matrix
	 * @param width  the number of columns of the input
	 */
	private void assertTangents(Sparse sparse, int width) {
		int count = sparse.columns.length;
		PackedCollection values = pattern(count, 3, 7);
		PackedCollection x = pattern(sparse.cols * width, 1, 5);
		PackedCollection inputTangent = pattern(sparse.cols * width, 4, 6);
		PackedCollection entryTangent = pattern(count, 5, 8);
		long bound = Math.max(count, (long) Math.max(sparse.rows, sparse.cols) * width);

		PackedCollection matrix = sparse.matrix(values);
		CollectionProducer input = cp(x.reshape(operandShape(sparse.cols, width)));
		CollectionProducer entries = cp(matrix);

		CollectionProducer inputJvp = matmul(cp(matrix), input).delta(input, ReduceAxis.IN,
				cp(inputTangent.reshape(operandShape(sparse.cols, width))));
		CollectionProducer entryJvp = matmul(entries, input).delta(entries, ReduceAxis.IN, cp(entryTangent));
		assertReduced(inputJvp, bound, SegmentedWeightedSumComputation.class);
		assertReduced(entryJvp, bound, SegmentedWeightedSumComputation.class);

		for (ComputeRequirement backend : backends()) {
			assertValues("input tangent on " + backend, sparse.product(host(values), host(inputTangent), width),
					evaluate(inputJvp, backend).toArray(0, sparse.rows * width));
			assertValues("entry tangent on " + backend, sparse.product(host(entryTangent), host(x), width),
					evaluate(entryJvp, backend).toArray(0, sparse.rows * width));
		}
	}

	/** The gradients of the product of the fixed matrix with a vector. */
	@Test(timeout = 120000)
	public void gradientsVector() { assertGradients(fixed(), 1); }

	/** The gradients of the product of the fixed matrix with a matrix of three columns. */
	@Test(timeout = 120000)
	public void gradientsMatrix() { assertGradients(fixed(), 3); }

	/** The gradients of the product of a matrix with a long row and a long column with a vector. */
	@Test(timeout = 120000)
	public void gradientsLongRowAndColumnVector() { assertGradients(longRowAndColumn(), 1); }

	/** The gradients of the product of a matrix with a long row and a long column with a matrix. */
	@Test(timeout = 120000)
	public void gradientsLongRowAndColumnMatrix() { assertGradients(longRowAndColumn(), 4); }

	/** The Jacobian-vector products of the fixed matrix, for a vector and a matrix input. */
	@Test(timeout = 120000)
	public void tangents() {
		assertTangents(fixed(), 1);
		assertTangents(fixed(), 3);
		assertTangents(longRowAndColumn(), 2);
	}

	/**
	 * The gradient, with respect to the input, of the Jacobian-vector product with respect to the
	 * entries is the transposed product of the matrix whose entries are the tangent:
	 * {@code T(t)ᵀ·g}. That product reads the tangent, which has one element per stored entry,
	 * through the shape of the matrix, so its transpose must be formed from that shape rather
	 * than from the one-dimensional shape of the tangent. It is reduced, and it is exact on every
	 * backend.
	 */
	@Test(timeout = 120000)
	public void entryTangentInputGradient() {
		Sparse sparse = fixed();
		int count = COLUMNS.length;
		PackedCollection values = pattern(count, 3, 7);
		PackedCollection tangent = pattern(count, 5, 8);
		PackedCollection g = pattern(sparse.rows, 2, 9);
		CollectionProducer entries = cp(sparse.matrix(values));
		CollectionProducer input = cp(pattern(sparse.cols, 1, 5));

		CollectionProducer gradient = matmul(entries, input).delta(entries, ReduceAxis.IN, cp(tangent))
				.delta(input, ReduceAxis.OUT, cp(g));
		assertReduced(gradient, Math.max(count, sparse.cols), SegmentedWeightedSumComputation.class);

		double[] expected = sparse.transposedProduct(host(tangent), host(g), 1);
		for (ComputeRequirement backend : backends()) {
			assertValues("entry tangent input gradient on " + backend, expected,
					evaluate(gradient, backend).toArray(0, sparse.cols));
		}
	}

	/**
	 * The gradient with respect to a batch of row vectors, as a dense layer passes its input, is
	 * the transposed product for each vector: {@code grad[b, j] = Σ_i A[i, j] g[b, i]}.
	 *
	 * <p>The product with a batch reads its input through a dense transpose, whose own reduced
	 * delta still contracts the Jacobian of that transpose (of the size of the input squared, not
	 * of the matrix). So this case asserts only that the transposed sparse product is formed, not
	 * the bound on the size of every computation in the graph.</p>
	 */
	@Test(timeout = 120000)
	public void gradientRowVectorBatch() {
		Sparse sparse = fixed();
		int batch = 2;
		PackedCollection values = pattern(OFFSETS[4], 3, 7);
		PackedCollection x = pattern(batch * sparse.cols, 1, 5);
		PackedCollection g = pattern(batch * sparse.rows, 2, 9);

		PackedCollection matrix = sparse.matrix(values);
		CollectionProducer input = cp(x.reshape(shape(batch, sparse.cols)).traverse(1));
		CollectionProducer gradient = matmul(cp(matrix), input)
				.delta(input, ReduceAxis.OUT, cp(g.reshape(shape(batch, sparse.rows, 1))));
		assertReduced(gradient, Long.MAX_VALUE, SegmentedWeightedSumComputation.class);

		double[] upstream = host(g);
		double[] expected = new double[batch * sparse.cols];
		for (int b = 0; b < batch; b++) {
			double[] row = sparse.transposedProduct(host(values),
					Arrays.copyOfRange(upstream, b * sparse.rows, (b + 1) * sparse.rows), 1);
			System.arraycopy(row, 0, expected, b * sparse.cols, sparse.cols);
		}

		for (ComputeRequirement backend : backends()) {
			assertValues("batch gradient on " + backend, expected,
					evaluate(gradient, backend).toArray(0, batch * sparse.cols));
		}
	}

	/**
	 * The input gradient reads the entries of the matrix when it is evaluated, not when it is
	 * built: after the entries are changed, the same delta gives the gradient of the new matrix, as
	 * the gradient of a trained sparse weight must.
	 */
	@Test(timeout = 120000)
	public void inputGradientReadsCurrentEntries() {
		Sparse sparse = fixed();
		PackedCollection g = pattern(sparse.rows, 2, 9);
		PackedCollection original = pack(2.0, -1.0, 3.0, -4.0, 5.0, 6.0, -7.0, 8.0);
		PackedCollection changed = pattern(OFFSETS[4], 6, 11);
		PackedCollection values = new PackedCollection(OFFSETS[4]);
		CollectionProducer input = cp(pattern(sparse.cols, 1, 5));
		CollectionProducer gradient = matmul(cp(sparse.matrix(values)), input)
				.delta(input, ReduceAxis.OUT, cp(g));

		for (ComputeRequirement backend : backends()) {
			values.setFrom(0, original);
			assertValues("before on " + backend, sparse.transposedProduct(host(original), host(g), 1),
					evaluate(gradient, backend).toArray(0, sparse.cols));

			values.setFrom(0, changed);
			assertValues("after on " + backend, sparse.transposedProduct(host(changed), host(g), 1),
					evaluate(gradient, backend).toArray(0, sparse.cols));
		}
	}

	/**
	 * The entry gradient of a matrix that stores no entries is its single unused element, which is
	 * zero: the kernel reads no entry, rather than reading the upstream gradient of row 0 and the
	 * input at the padding column and returning their product. The input gradient is zero too.
	 */
	@Test(timeout = 60000)
	public void gradientsEmpty() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(2, new int[] { 0, 0, 0, 0 }, new int[0]);
		PackedCollection matrix = new PackedCollection(shape(3, 2), 0, pack(0.0), 0, order);
		CollectionProducer entries = cp(matrix);
		CollectionProducer input = cp(pack(3.0, 5.0));
		CollectionProducer upstream = cp(pack(2.0, 4.0, 6.0));

		CollectionProducer entryGradient = matmul(entries, cp(pack(3.0, 5.0))).delta(entries, ReduceAxis.OUT, upstream);
		CollectionProducer inputGradient = matmul(cp(matrix), input).delta(input, ReduceAxis.OUT, upstream);
		assertReduced(entryGradient, 3, SegmentedOuterProductComputation.class);
		assertReduced(inputGradient, 3, SegmentedWeightedSumComputation.class);

		for (ComputeRequirement backend : backends()) {
			PackedCollection actualEntries = evaluate(entryGradient, backend);
			assertEquals(1, actualEntries.getShape().getTotalSize());
			assertValues("empty entry gradient on " + backend, new double[] { 0.0 }, actualEntries.toArray(0, 1));
			assertValues("empty input gradient on " + backend, new double[] { 0.0, 0.0 },
					evaluate(inputGradient, backend).toArray(0, 2));
		}
	}

	/**
	 * The entry tangent of a matrix that stores no entries is its single unused element, the same
	 * padding as its entry gradient. The Jacobian-vector product with that tangent is still the
	 * reduced segmented product, not the general Jacobian, and it is zero in every row because the
	 * kernel reads no entry.
	 */
	@Test(timeout = 60000)
	public void tangentEmpty() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(2, new int[] { 0, 0, 0, 0 }, new int[0]);
		PackedCollection matrix = new PackedCollection(shape(3, 2), 0, pack(0.0), 0, order);
		CollectionProducer entries = cp(matrix);
		CollectionProducer input = cp(pack(3.0, 5.0));

		CollectionProducer entryJvp = matmul(entries, input).delta(entries, ReduceAxis.IN, cp(pack(7.0)));
		assertReduced(entryJvp, 3, SegmentedWeightedSumComputation.class);

		for (ComputeRequirement backend : backends()) {
			assertValues("empty entry tangent on " + backend, new double[] { 0.0, 0.0, 0.0 },
					evaluate(entryJvp, backend).toArray(0, 3));
		}
	}

	/**
	 * Both gradients of the product of a matrix with more than {@code 2^24} rows and entries, whose
	 * logical size exceeds the range of an {@code int}, with a vector are exact: the gradient of
	 * the entries near the end reads the upstream gradient at rows above {@code 2^24}, where a
	 * single-precision index would land on a neighbouring row, whose upstream gradient differs, and
	 * the input gradient
	 * sums each column of the matrix.
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void gradientsBeyondFloatRange() {
		int period = 13;
		int rows = (1 << 24) + 37;
		int cols = 200;
		int[] offsets = new int[rows + 1];
		int[] columns = new int[rows];
		for (int i = 0; i <= rows; i++) offsets[i] = i;
		for (int i = 0; i < rows; i++) columns[i] = (int) ((31L * i) % cols);

		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(cols, offsets, columns);
		try (PackedCollection values = new PackedCollection(rows).fill(1.0);
			 PackedCollection upstream = integers(0, rows).mod(period).reshape(shape(rows, 1)).evaluate();
			 PackedCollection vector = integers(0, cols).mod(c(7)).add(c(1.0)).evaluate()) {
			PackedCollection matrix = new PackedCollection(shape(rows, cols), 0, values, 0, order);
			assertTrue("the logical size should exceed an int", matrix.getShape().getTotalSizeLong() > Integer.MAX_VALUE);
			assertTrue("the rows should exceed 2^24", rows > (1 << 24));

			CollectionProducer input = cp(vector);
			CollectionProducer entries = cp(matrix);
			PackedCollection inputGradient = matmul(cp(matrix), input)
					.delta(input, ReduceAxis.OUT, cp(upstream)).evaluate();
			PackedCollection entryGradient = matmul(entries, cp(vector))
					.delta(entries, ReduceAxis.OUT, cp(upstream)).evaluate();

			double[] x = host(vector);
			double[] g = host(upstream);
			for (int i = rows - 4096; i < rows; i++) {
				assertEquals("upstream " + i, i % period, g[i]);
			}

			double[] expectedInput = new double[cols];
			for (int i = 0; i < rows; i++) expectedInput[columns[i]] += g[i];
			double[] actualInput = inputGradient.toArray(0, cols);
			for (int j = 0; j < cols; j++) {
				assertEquals("input gradient " + j, expectedInput[j], actualInput[j]);
			}

			assertEquals(rows, entryGradient.getShape().getTotalSize());
			double[] actualEntries = entryGradient.toArray(rows - 4096, 4096);
			for (int k = 0; k < 4096; k++) {
				int entry = rows - 4096 + k;
				assertEquals("entry gradient " + entry, g[entry] * x[columns[entry]], actualEntries[k]);
			}
		}
	}
}
