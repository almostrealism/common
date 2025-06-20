/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.algebra.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class WeightedSumTests implements TestFeatures {

	@Test
	public void broadcast() {
		int c1 = 3;
		int c2 = 2;

		TraversalPolicy leftShape = shape(c1, 1);
		TraversalPolicy rightShape = shape(1, c2);

		PackedCollection<?> a = new PackedCollection<>(leftShape).randFill();
		PackedCollection<?> b = new PackedCollection<>(rightShape).randFill();

		TraversalPolicy resultShape = shape(c1, c2);
		TraversalPolicy leftPosition = leftShape
				.repeat(1, c2);
		TraversalPolicy rightPosition = rightShape
				.repeat(0, c1);
		TraversalPolicy groupShape = shape(1, 1);

		List<int[]> resultPositions = resultShape.inputPositions().collect(Collectors.toList());
		List<int[]> leftPositions = leftPosition.inputPositions().collect(Collectors.toList());
		List<int[]> rightPositions = rightPosition.inputPositions().collect(Collectors.toList());

		for (int i = 0; i < resultPositions.size(); i++) {
			int pos[] = resultPositions.get(i);
			int left[] = leftPositions.get(i);
			int right[] = rightPositions.get(i);
			log("result" + s(pos) + " = a" + s(left) + " * b" + s(right));
		}

		CollectionProducer<PackedCollection<?>> result =
				weightedSum("broadcast",
						resultShape,
						leftPosition, rightPosition,
						groupShape, groupShape,
						cp(a), cp(b));
		PackedCollection<?> out = result.evaluate();

		for (int i = 0; i < c1; i++) {
			for (int j = 0; j < c2; j++) {
				double sum = a.valueAt(i, 0) * b.valueAt(0, j);
				log(sum + " vs " + out.valueAt(i, j));
				assertEquals(sum, out.valueAt(i, j));
			}
		}

		result = broadcast(cp(a), cp(b));
		out = result.evaluate();

		for (int i = 0; i < c1; i++) {
			for (int j = 0; j < c2; j++) {
				double sum = a.valueAt(i, 0) * b.valueAt(0, j);
				log(sum + " vs " + out.valueAt(i, j));
				assertEquals(sum, out.valueAt(i, j));
			}
		}
	}

	@Test
	public void sumColumn() {
		int r = 3;
		int c1 = 2;
		int c2 = 3;

		TraversalPolicy leftShape = shape(r, c1, 1);
		TraversalPolicy rightShape = shape(r, 1, c2);

		PackedCollection<?> a = new PackedCollection<>(leftShape).randFill();
		PackedCollection<?> b = new PackedCollection<>(rightShape).randFill();

		TraversalPolicy resultShape = shape(1, c1, c2);
		TraversalPolicy leftPosition = leftShape.repeat(2, c2);
		TraversalPolicy rightPosition = rightShape.repeat(1, c1);
		TraversalPolicy groupShape = shape(r, 1, 1);

		List<int[]> resultPositions = resultShape.inputPositions().collect(Collectors.toList());
		List<int[]> leftPositions = leftPosition.inputPositions().collect(Collectors.toList());
		List<int[]> rightPositions = rightPosition.inputPositions().collect(Collectors.toList());

		for (int i = 0; i < resultPositions.size(); i++) {
			int pos[] = resultPositions.get(i);
			int left[] = leftPositions.get(i);
			int right[] = rightPositions.get(i);
			log("result" + s(pos) + " = a" + s(left) + " * b" + s(right));
		}

		CollectionProducer<PackedCollection<?>> result =
				weightedSum("sumColumn",
						resultShape,
						leftPosition, rightPosition,
						groupShape, groupShape,
						cp(a), cp(b));
		PackedCollection<?> out = result.evaluate();

		sumColumnAssertions(
				a.reshape(1, r, c1),
				b.reshape(1, r, c2),
				out.reshape(1, c1, c2));
	}

	@Test
	public void sumColumnBatch() {
		int bs = 1;
		int r = 3;
		int c1 = 2;
		int c2 = 3;

		TraversalPolicy leftShape = shape(bs, r, c1, 1);
		TraversalPolicy rightShape = shape(bs, r, 1, c2);

		PackedCollection<?> a = new PackedCollection<>(leftShape).randFill();
		PackedCollection<?> b = new PackedCollection<>(rightShape).randFill();

		TraversalPolicy resultShape = shape(bs, 1, c1, c2);
		TraversalPolicy leftPosition = leftShape.repeat(3, c2);
		TraversalPolicy rightPosition = rightShape.repeat(2, c1);
		TraversalPolicy groupShape = shape(1, r, 1, 1);

		CollectionProducer<PackedCollection<?>> result =
				weightedSum("sumColumn",
						resultShape,
						leftPosition, rightPosition,
						groupShape, groupShape,
						cp(a), cp(b));
		PackedCollection<?> out = result.evaluate();

		sumColumnAssertions(
				a.reshape(bs, r, c1),
				b.reshape(bs, r, c2),
				out.reshape(bs, c1, c2));
	}

	@Test
	public void sumColumnRepeatBatch() {
		int bs = 1;
		int r = 3;
		int c1 = 2;
		int c2 = 3;

		int d = 1;
		PackedCollection<?> a = new PackedCollection(shape(bs, r, c1)).randFill();
		PackedCollection<?> b = new PackedCollection(shape(bs, r, c2)).randFill();

		CollectionProducer<PackedCollection<?>> pa = c(a)
				.traverse(d)
				.enumerate(d + 1, 1)
				// -> (bs, c1, r)
				.traverse(d + 1)
				.repeat(c2); // -> (bs, c1, c2, r)
		CollectionProducer<PackedCollection<?>> pb = c(b)
				.traverse(d)
				.enumerate(d + 1, 1)
				// -> (bs, c2, r)
				.repeat(c1); // -> (bs, c1, c2, r)
		CollectionProducer<PackedCollection<?>> out = multiply(pa, pb).sum(d + 2)
				.reshape(bs, c1, c2).traverseEach();
		sumColumnAssertions(
				a.reshape(bs, r, c1),
				b.reshape(bs, r, c2),
				out.evaluate().reshape(bs, c1, c2));
	}

	protected void sumColumnAssertions(PackedCollection<?> a, PackedCollection<?> b, PackedCollection<?> out) {
		int bs = a.getShape().length(0);
		int r = a.getShape().length(1);
		int c1 = a.getShape().length(2);
		int c2 = b.getShape().length(2);

		for (int n = 0; n < bs; n++) {
			for (int i = 0; i < c1; i++) {
				for (int j = 0; j < c2; j++) {
					double sum = 0;

					for (int k = 0; k < r; k++) {
						sum += a.valueAt(n, k, i) * b.valueAt(n, k, j);
					}

					log(sum + " vs " + out.valueAt(n, i, j));
					assertEquals(sum, out.valueAt(n, i, j));
				}
				log("---");
			}
		}
	}

	@Test
	public void similarity() {
		int bs = 3;
		int c = 4;

		int dim = 3;
		int s1 = 2; // 4;
		int s2 = 5;

		PackedCollection<?> a = new PackedCollection(shape(bs, c, dim, s1)).randFill();
		PackedCollection<?> b = new PackedCollection(shape(bs, c, dim, s2)).randFill();

		CollectionProducer<PackedCollection<?>> pa = c(a)
				.traverse(2)
				.enumerate(3, 1)
				// -> (bs, c, s1, dim)
				.traverse(3)
				.repeat(s2); // -> (bs, c, s1, s2, dim)
		CollectionProducer<PackedCollection<?>> pb = c(b)
				.traverse(2)
				.enumerate(3, 1)
				// -> (bs, c, s2, dim)
				.repeat(s1); // -> (bs, c, s1, s2, dim)
		CollectionProducer<PackedCollection<?>> out = multiply(pa, pb).sum(4)
				.reshape(bs, c, s1, s2);

		TraversalPolicy leftShape = shape(bs, c, dim, s1, 1);
		TraversalPolicy rightShape = shape(bs, c, dim, 1, s2);

		TraversalPolicy resultShape = shape(bs, c, 1, s1, s2);
		TraversalPolicy leftPosition = leftShape.repeat(4, s2);
		TraversalPolicy rightPosition = rightShape.repeat(3, s1);
		TraversalPolicy groupShape = shape(1, 1, dim, 1, 1);

		CollectionProducer<PackedCollection<?>> result =
				weightedSum("similarity",
						resultShape,
						leftPosition, rightPosition,
						groupShape, groupShape,
						reshape(leftShape, cp(a)),
						reshape(rightShape, cp(b)));

		compare(out, result.reshape(bs, c, s1, s2));
	}


	@Test
	public void scaledDotProduct() {
		int batchSize = 2;
		int heads = 3;
		int seqLenA = 5;
		int seqLenB = 7;
		int dim = 4;

		// Create input tensors
		// a: (batch, heads, seqLenA, dim)
		// b: (batch, heads, dim, seqLenB)
		PackedCollection<?> a = new PackedCollection<>(shape(batchSize, heads, seqLenA, dim))
				.fill(pos -> Math.random());
		PackedCollection<?> b = new PackedCollection<>(shape(batchSize, heads, dim, seqLenB))
				.fill(pos -> Math.random());

		// Compute expected result manually
		// Result should have shape (batch, heads, seqLenA, seqLenB)
		PackedCollection<?> expected = new PackedCollection<>(shape(batchSize, heads, seqLenA, seqLenB));

		for (int batch = 0; batch < batchSize; batch++) {
			for (int head = 0; head < heads; head++) {
				for (int i = 0; i < seqLenA; i++) {
					for (int j = 0; j < seqLenB; j++) {
						double sum = 0.0;
						for (int k = 0; k < dim; k++) {
							sum += a.valueAt(batch, head, i, k) * b.valueAt(batch, head, k, j);
						}
						expected.setValueAt(sum, batch, head, i, j);
					}
				}
			}
		}

		CollectionProducer<PackedCollection<?>> result = scaledDotProduct(cp(a), cp(b));
		PackedCollection<?> actual = result.evaluate();

		// Verify shapes match
		assertEquals(expected.getShape().traverse(0), actual.getShape().traverse(0));

		for (int batch = 0; batch < batchSize; batch++) {
			for (int head = 0; head < heads; head++) {
				for (int i = 0; i < seqLenA; i++) {
					for (int j = 0; j < seqLenB; j++) {
						assertEquals(expected.valueAt(batch, head, i, j), actual.valueAt(batch, head, i, j));
					}
				}
			}
		}
	}

	@Test
	public void scaledDotProductPermute() {
		int batchSize = 2;
		int heads = 3;
		int seqLen = 6;
		int dim = 4;

		// Test case for Q @ K^T pattern used in attention
		// q: (batch, heads, seqLen, dim)
		// k: (batch, heads, seqLen, dim)
		// Result after k transpose: (batch, heads, seqLen, seqLen)
		PackedCollection<?> q = new PackedCollection<>(shape(batchSize, heads, seqLen, dim))
				.fill(pos -> Math.random());
		PackedCollection<?> k = new PackedCollection<>(shape(batchSize, heads, seqLen, dim))
				.fill(pos -> Math.random());

		// Compute expected result for Q @ K^T
		PackedCollection<?> expected = new PackedCollection<>(shape(batchSize, heads, seqLen, seqLen));

		for (int batch = 0; batch < batchSize; batch++) {
			for (int head = 0; head < heads; head++) {
				for (int i = 0; i < seqLen; i++) {
					for (int j = 0; j < seqLen; j++) {
						double sum = 0.0;
						for (int d = 0; d < dim; d++) {
							// Q[i,d] * K[j,d] (K transposed)
							sum += q.valueAt(batch, head, i, d) * k.valueAt(batch, head, j, d);
						}

						expected.setValueAt(sum, batch, head, i, j);
					}
				}
			}
		}

		// Test with transposed K
		// We need to transpose last two dimensions of k: (batch, heads, seqLen, dim) -> (batch, heads, dim, seqLen)
		CollectionProducer<PackedCollection<?>> kTransposed = cp(k)
				.reshape(batchSize, heads, seqLen, dim)
				.permute(0, 1, 3, 2);

		CollectionProducer<PackedCollection<?>> result = scaledDotProduct(cp(q), kTransposed);
		PackedCollection<?> actual = result.evaluate();

		// Verify shapes match
		assertEquals(expected.getShape().traverse(0), actual.getShape().traverse(0));

		// Compare results
		double diff = compare(expected, actual);
		log("batchMatrixMultiply with transpose difference: " + diff);
		assertTrue("Batch matrix multiplication with transpose differs from expected", diff < 1e-6);
	}

	@Test
	public void scaledDotProductTranspose() {
		int batchSize = 2;
		int heads = 3;
		int seqLen = 6;
		int dim = 4;

		// Test case for Q @ K^T pattern used in attention
		// q: (batch, heads, seqLen, dim)
		// k: (batch, heads, seqLen, dim)
		// Result after k transpose: (batch, heads, seqLen, seqLen)
		PackedCollection<?> q = new PackedCollection<>(shape(batchSize, heads, seqLen, dim))
				.fill(pos -> Math.random());
		PackedCollection<?> k = new PackedCollection<>(shape(batchSize, heads, seqLen, dim))
				.fill(pos -> Math.random());

		// Compute expected result for Q @ K^T
		PackedCollection<?> expected = new PackedCollection<>(shape(batchSize, heads, seqLen, seqLen));

		for (int batch = 0; batch < batchSize; batch++) {
			for (int head = 0; head < heads; head++) {
				for (int i = 0; i < seqLen; i++) {
					for (int j = 0; j < seqLen; j++) {
						double sum = 0.0;
						for (int d = 0; d < dim; d++) {
							// Q[i,d] * K[j,d] (K transposed)
							sum += q.valueAt(batch, head, i, d) * k.valueAt(batch, head, j, d);
						}

						expected.setValueAt(sum, batch, head, i, j);
					}
				}
			}
		}
		
		CollectionProducer<PackedCollection<?>> result = scaledDotProduct(cp(q), cp(k), true);
		PackedCollection<?> actual = result.evaluate();

		// Verify shapes match
		assertEquals(expected.getShape().traverse(0), actual.getShape().traverse(0));

		// Compare results
		double diff = compare(expected, actual);
		log("batchMatrixMultiply with transpose difference: " + diff);
		assertTrue("Batch matrix multiplication with transpose differs from expected", diff < 1e-6);
	}
}
