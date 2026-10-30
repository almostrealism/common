/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProductComputation;
import org.almostrealism.collect.computations.FixedCollectionComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for matrix multiplication operations on PackedCollection.
 */
public class MatrixCollectionTests extends TestSuiteBase {
	/**
	 * Tests matrix multiply with repeat.
	 */
	@Test(timeout = 30000)
	public void matrixMultiplyRepeat() {
		int size = 768;
		int n = size;
		int d = size;

		PackedCollection x = new PackedCollection(shape(n));
		x.randFill();

		PackedCollection weight = new PackedCollection(shape(d, n));
		weight.randFill();

		kernelTest(() -> multiply(traverseEach(p(weight)), traverseEach(repeat(d, p(x)))).traverse(1).sum(),
				output -> {
					for (int i = 0; i < d; i++) {
						double v = 0.0;

						for (int j = 0; j < n; j++) {
							v += weight.valueAt(i, j) * x.valueAt(j);
						}

						assertEquals(output.valueAt(i, 0), v);
					}
				}, false, false, true);
	}

	/**
	 * Scaling a constant too large to embed as an expression, such as a large identity matrix,
	 * stays a constant computation evaluated by a kernel instead of being folded on the host
	 * into a copied buffer, which would hold every element on the Java heap; a constant small
	 * enough to embed is still folded. Both produce the scaled values. The two identity sizes on
	 * either side of {@link ScopeSettings#maxConditionSize} pin where the decision changes.
	 */
	@Test(timeout = 60000)
	public void scaledConstantFoldsOnlyWhenEmbeddable() {
		int size = 64;
		CollectionProducer scaled = identity(size).multiply(0.5);
		assertTrue(scaled.isConstant());
		assertTrue(scaled instanceof CollectionProductComputation);

		int below = (int) Math.sqrt(ScopeSettings.maxConditionSize - 1);
		int above = below + 1;
		assertTrue(identity(below).multiply(2.0) instanceof FixedCollectionComputation);
		assertTrue(identity(above).multiply(2.0) instanceof CollectionProductComputation);

		PackedCollection matrix = scaled.evaluate();
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				assertEquals(i == j ? 0.5 : 0.0, matrix.valueAt(i, j));
			}
		}

		CollectionProducer folded = identity(2).multiply(3.0);
		assertTrue(folded instanceof FixedCollectionComputation);

		PackedCollection small = folded.evaluate();
		assertEquals(3.0, small.valueAt(0, 0));
		assertEquals(0.0, small.valueAt(0, 1));
		assertEquals(0.0, small.valueAt(1, 0));
		assertEquals(3.0, small.valueAt(1, 1));
	}
}
