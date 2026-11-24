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

import io.almostrealism.collect.Algebraic;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that a matrix assembled by concatenating single-value cells is not mistaken for
 * a uniform diagonal matrix. Every cell, being a single value, is trivially diagonal, but
 * the concatenation places each cell at its own position, so the grid is an arbitrary
 * matrix and multiplying by it must contract over its rows rather than scale by a
 * "diagonal scalar".
 *
 * @author Michael Murray
 */
public class ConcatenateDiagonalTests extends TestSuiteBase {

	/**
	 * Builds the {@code [2, 2]} matrix {@code [[2, 0], [0, 3]]} from four single-value
	 * cells, the way a per-cell routing grid is assembled.
	 *
	 * @return the assembled grid
	 */
	private CollectionProducer grid() {
		return concat(cp(pack(2.0)), cp(pack(0.0)), cp(pack(0.0)), cp(pack(3.0)))
				.reshape(shape(2, 2));
	}

	/** A concatenated grid of single values reports neither a diagonal nor a diagonal scalar. */
	@Test(timeout = 30000)
	public void concatenatedGridIsNotDiagonal() {
		CollectionProducer matrix = grid();
		Assert.assertFalse(Algebraic.isDiagonal(2, matrix));
		Assert.assertFalse(Algebraic.getDiagonalScalar(2, matrix).isPresent());
	}

	/** Multiplying channel rows by a concatenated grid contracts over the grid's rows. */
	@Test(timeout = 60000)
	public void matmulByConcatenatedGrid() {
		CollectionProducer input = integers(1, 9).reshape(shape(2, 4));

		PackedCollection out = matmul(grid(), input).evaluate();
		Assert.assertEquals(8, out.getShape().getTotalSize());

		double[] expected = {2.0, 4.0, 6.0, 8.0, 15.0, 18.0, 21.0, 24.0};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], out.toDouble(i));
		}
	}
}
