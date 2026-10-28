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

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.SegmentedWeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.stats.DistributionFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests that a {@link PackedCollection} read through a {@link CompressedRowTraversalOrdering} is
 * an ordinary collection to the existing computations of the framework: each test applies one
 * existing computation to the sparse matrix and to the same matrix stored densely, on every
 * available backend, and requires the two results to agree in shape and in every element.
 *
 * <p>The matrix is the {@code (4, 5)} matrix</p>
 * <pre>
 * [[-1,  0, 0,  2,  0],
 *  [ 0,  0, 0,  0,  0],
 *  [ 3, -4, 5,  6, -7],
 *  [ 0,  0, 8,  0,  0]]
 * </pre>
 * <p>stored as eight entries, with an empty row, a full row and a row whose entries are stored
 * out of column order, so that the search of every row length up to the longest is exercised.</p>
 */
public class CompressedRowOrderedComputationTests extends TestSuiteBase implements MatrixFeatures, DistributionFeatures {
	/** The row offsets of the matrix. */
	private static final int[] OFFSETS = { 0, 2, 2, 7, 8 };

	/** The column of each entry of the matrix. */
	private static final int[] COLUMNS = { 3, 0, 0, 1, 2, 3, 4, 2 };

	/**
	 * Returns the backends to evaluate on: the CPU, and the GPU when one is available.
	 *
	 * @return the compute requirements selecting each backend
	 */
	private List<ComputeRequirement> backends() {
		return Stream.of(ComputeRequirement.CPU, ComputeRequirement.GPU)
				.filter(r -> Hardware.getLocalHardware().isAvailable(r))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the matrix as a collection of its eight entries read through a
	 * {@link CompressedRowTraversalOrdering}, asserting that the collection carries the ordering.
	 *
	 * @return the sparse matrix
	 */
	private PackedCollection sparse() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(5, OFFSETS, COLUMNS);
		PackedCollection matrix = new PackedCollection(shape(4, 5), 0,
				pack(2.0, -1.0, 3.0, -4.0, 5.0, 6.0, -7.0, 8.0), 0, order);
		assertTrue("the matrix should carry its ordering", matrix.getShape().getOrder() == order);
		return matrix;
	}

	/**
	 * Returns the matrix stored densely.
	 *
	 * @return the dense matrix
	 */
	private PackedCollection dense() {
		return pack(-1.0, 0.0, 0.0, 2.0, 0.0,
				0.0, 0.0, 0.0, 0.0, 0.0,
				3.0, -4.0, 5.0, 6.0, -7.0,
				0.0, 0.0, 8.0, 0.0, 0.0).reshape(shape(4, 5));
	}

	/**
	 * Evaluates the given computation of the sparse matrix and of the dense matrix on every
	 * available backend, and asserts that the results agree in shape and element by element.
	 *
	 * @param operation builds the computation from a producer of the matrix
	 */
	private void assertMatchesDense(Function<CollectionProducer, CollectionProducer> operation) {
		for (ComputeRequirement backend : backends()) {
			PackedCollection expected = evaluate(operation.apply(cp(dense())), backend);
			PackedCollection actual = evaluate(operation.apply(cp(sparse())), backend);
			assertEquals("shape on " + backend, expected.getShape().getTotalSize(),
					actual.getShape().getTotalSize());

			double[] e = expected.toArray(0, expected.getShape().getTotalSize());
			double[] a = actual.toArray(0, actual.getShape().getTotalSize());
			for (int i = 0; i < e.length; i++) {
				assertEquals("element " + i + " on " + backend, e[i], a[i]);
			}
		}
	}

	/**
	 * Evaluates the given computation with the given backend required.
	 *
	 * @param producer the computation
	 * @param backend  the backend
	 * @return the result
	 */
	private PackedCollection evaluate(CollectionProducer producer, ComputeRequirement backend) {
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(backend));

		try {
			return producer.evaluate();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}
	}

	/** Adding a constant. */
	@Test(timeout = 60000)
	public void addConstant() { assertMatchesDense(m -> m.add(c(1.5))); }

	/** Adding the matrix to itself, so that both operands are read through the ordering. */
	@Test(timeout = 60000)
	public void addSelf() { assertMatchesDense(m -> m.add(m)); }

	/** Subtracting a constant. */
	@Test(timeout = 60000)
	public void subtractConstant() { assertMatchesDense(m -> m.subtract(c(2.0))); }

	/** Multiplying the matrix by a dense matrix element-wise. */
	@Test(timeout = 60000)
	public void multiplyDense() { assertMatchesDense(m -> m.multiply(cp(dense()))); }

	/** Dividing by a constant. */
	@Test(timeout = 60000)
	public void divideConstant() { assertMatchesDense(m -> m.divide(c(4.0))); }

	/** Negation. */
	@Test(timeout = 60000)
	public void minus() { assertMatchesDense(CollectionProducer::minus); }

	/** Raising to a power. */
	@Test(timeout = 60000)
	public void pow() { assertMatchesDense(m -> m.pow(2.0)); }

	/** The exponential. */
	@Test(timeout = 60000)
	public void exp() { assertMatchesDense(CollectionProducer::exp); }

	/** The absolute value followed by the square root. */
	@Test(timeout = 60000)
	public void absSqrt() { assertMatchesDense(m -> m.abs().sqrt()); }

	/** Rectification. */
	@Test(timeout = 60000)
	public void rectify() { assertMatchesDense(m -> rectify(m)); }

	/** The element-wise maximum with a constant. */
	@Test(timeout = 60000)
	public void maxElementwise() { assertMatchesDense(m -> max(m, c(0.5))); }

	/** The remainder modulo a constant. */
	@Test(timeout = 60000)
	public void mod() { assertMatchesDense(m -> m.mod(c(3.0))); }

	/** A comparison selecting one of two values. */
	@Test(timeout = 60000)
	public void greaterThan() { assertMatchesDense(m -> greaterThan(m, c(0.0), c(1.0), c(-1.0))); }

	/** The logistic sigmoid. */
	@Test(timeout = 60000)
	public void sigmoid() { assertMatchesDense(CollectionProducer::sigmoid); }

	/** The hyperbolic tangent. */
	@Test(timeout = 60000)
	public void tanh() { assertMatchesDense(m -> tanh(m)); }

	/** The sum of every element. */
	@Test(timeout = 60000)
	public void sumAll() { assertMatchesDense(CollectionProducer::sum); }

	/** The sum of each row. */
	@Test(timeout = 60000)
	public void sumRows() { assertMatchesDense(m -> m.traverse(1).sum()); }

	/** The maximum of each row. */
	@Test(timeout = 60000)
	public void maxRows() { assertMatchesDense(m -> m.traverse(1).max()); }

	/** The position of the maximum of each row. */
	@Test(timeout = 60000)
	public void indexOfMaxRows() { assertMatchesDense(m -> m.traverse(1).indexOfMax()); }

	/** The mean of each row. */
	@Test(timeout = 60000)
	public void meanRows() { assertMatchesDense(m -> m.mean(1)); }

	/** The variance of each row. */
	@Test(timeout = 60000)
	public void varianceRows() { assertMatchesDense(m -> m.variance(1)); }

	/** The softmax of each row. */
	@Test(timeout = 60000)
	public void softmaxRows() { assertMatchesDense(m -> softmax(m.traverse(1))); }

	/** A reshape consumed by an element-wise product. */
	@Test(timeout = 60000)
	public void reshape() { assertMatchesDense(m -> m.reshape(shape(5, 4)).multiply(c(2.0))); }

	/** A subset that straddles the empty and the full row. */
	@Test(timeout = 60000)
	public void subset() { assertMatchesDense(m -> subset(shape(2, 3), m, 1, 1)); }

	/** Padding on both axes. */
	@Test(timeout = 60000)
	public void pad() { assertMatchesDense(m -> pad(m, 1, 1)); }

	/** Repetition of the whole matrix. */
	@Test(timeout = 60000)
	public void repeat() { assertMatchesDense(m -> m.repeat(3)); }

	/** The transpose. */
	@Test(timeout = 60000)
	public void transpose() { assertMatchesDense(CollectionProducer::transpose); }

	/** A permutation of the axes. */
	@Test(timeout = 60000)
	public void permute() { assertMatchesDense(m -> m.permute(1, 0)); }

	/** An enumeration of overlapping column windows. */
	@Test(timeout = 60000)
	public void enumerate() { assertMatchesDense(m -> m.enumerate(1, 2, 1)); }

	/** Concatenation of the matrix with a multiple of itself along the rows. */
	@Test(timeout = 60000)
	public void concat() { assertMatchesDense(m -> concat(0, m, m.multiply(c(2.0)))); }

	/** The magnitude of each row. */
	@Test(timeout = 60000)
	public void magnitudeRows() { assertMatchesDense(m -> m.magnitude(1)); }

	/** The product with a vector. */
	@Test(timeout = 60000)
	public void matmulVector() {
		assertMatchesDense(m -> matmul(m, cp(pack(1.0, 2.0, 3.0, 4.0, 5.0))));
	}

	/** The product with a matrix. */
	@Test(timeout = 60000)
	public void matmulMatrix() {
		assertMatchesDense(m -> matmul(m, cp(pack(1.0, -1.0, 2.0, 0.5, 3.0,
				-2.0, 4.0, 1.0, 0.0, 5.0,
				2.0, -3.0, 1.0, 1.0, 1.0).reshape(shape(5, 3)))));
	}

	/** The product with the transpose of a matrix. */
	@Test(timeout = 60000)
	public void matmulTransposed() {
		assertMatchesDense(m -> multiplyTransposed(m, cp(pack(1.0, -1.0, 2.0, 0.5, 3.0,
				-2.0, 4.0, 1.0, 0.0, 5.0).reshape(shape(2, 5))).traverse(0)));
	}

	/**
	 * The product of the sparse matrix is computed by enumerating the entries of its rows rather
	 * than by reading every logical element through the ordering.
	 */
	@Test(timeout = 60000)
	public void matmulEnumeratesRows() {
		assertTrue(matmul(cp(sparse()), cp(pack(1.0, 2.0, 3.0, 4.0, 5.0)))
				instanceof SegmentedWeightedSumComputation);
	}

	/**
	 * The product of a {@code 300 × 200} matrix holding one full row with a {@code (200, 3)}
	 * matrix is correct. Read through the ordering, every logical element of this matrix is a
	 * search as long as its longest row, and the dense product of it failed to compile; the
	 * product enumerates the 200 entries instead.
	 */
	@Test(timeout = 120000)
	public void matmulLongRow() {
		int rows = 300;
		int cols = 200;
		int[] offsets = new int[rows + 1];
		int[] columns = new int[cols];
		for (int i = 0; i <= rows; i++) offsets[i] = i <= 7 ? 0 : cols;
		for (int j = 0; j < cols; j++) columns[j] = cols - 1 - j;

		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(cols, offsets, columns);
		try (PackedCollection values = integers(0, cols).mod(5).evaluate();
			 PackedCollection x = integers(0, cols * 3).mod(7).evaluate()) {
			PackedCollection matrix = new PackedCollection(shape(rows, cols), 0, values, 0, order);
			PackedCollection y = matmul(cp(matrix), cp(x.reshape(shape(cols, 3)))).evaluate();
			assertEquals(rows * 3, y.getShape().getTotalSize());

			double[] result = y.toArray();
			for (int i = 0; i < rows; i++) {
				for (int w = 0; w < 3; w++) {
					double expected = 0.0;
					if (i == 7) {
						for (int k = 0; k < cols; k++) expected += (k % 5) * ((columns[k] * 3 + w) % 7);
					}

					assertEquals("element (" + i + ", " + w + ")", expected, result[i * 3 + w]);
				}
			}
		}
	}
}
