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

package org.almostrealism.layers.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Checks that {@link LayerFeatures#cacheWrite} and {@link LayerFeatures#cacheRead} address every
 * element of a cache exactly when the cache holds more than {@code 2^24} elements, the bound below
 * which a single-precision backend represents every integer.
 *
 * <p>Each row is written with values that differ from those of its neighbouring elements and of
 * every other row, so a write or read that lands one element (or one row) away is detected. The
 * values themselves stay below {@code 2^24}, so only the addressing can be inexact.</p>
 */
public class CacheRowAddressingTests extends TestSuiteBase implements LayerFeatures {

	/** Number of rows in the cache. */
	private static final int ROWS = 20;

	/**
	 * Elements per row; {@code ROWS * ROW_SIZE} exceeds {@code 2^24}. The row size is odd, so the
	 * first element of a row past {@code 2^24} is at an odd position, which a single-precision
	 * product {@code row * ROW_SIZE} rounds.
	 */
	private static final int ROW_SIZE = 999_999;

	/**
	 * The value written to element {@code i} of row {@code row}: distinct from its neighbours in
	 * the row and from the same element of any other row, and exact in single precision.
	 *
	 * @param row the row
	 * @param i   the element within the row
	 * @return the value
	 */
	private static double written(int row, int i) {
		return (i % 1000) * 32.0 + row;
	}

	/**
	 * Writes every row of a {@code [20, 999999]} cache through {@code cacheWrite}, then reads each
	 * back through {@code cacheRead} and requires every element to be the value written to it.
	 */
	@Test(timeout = 600000)
	public void rowsBeyondSinglePrecisionRangeRoundTrip() {
		Assert.assertTrue(ROWS * (long) ROW_SIZE > (1L << 24));

		PackedCollection position = new PackedCollection(shape(1));
		try (PackedCollection cache = new PackedCollection(shape(ROWS, ROW_SIZE))) {
			Model writer = new Model(shape(ROW_SIZE));
			writer.add(cacheWrite(shape(ROW_SIZE), cp(cache), p(position)));
			CompiledModel write = writer.compile(false);

			Model reader = new Model(shape(ROW_SIZE));
			reader.add(cacheRead(shape(ROW_SIZE), cp(cache), p(position)));
			CompiledModel read = reader.compile(false);

			for (int row = 0; row < ROWS; row++) {
				position.fill(row);
				write.forward(integers(0, ROW_SIZE).mod(c(1000.0)).multiply(32.0).add(row).evaluate());
			}

			double[] stored = cache.toArray();
			double[][] rows = new double[ROWS][];
			for (int row = 0; row < ROWS; row++) {
				position.fill(row);
				rows[row] = read.forward(new PackedCollection(shape(ROW_SIZE))).toArray();
			}

			for (int row = 0; row < ROWS; row++) {
				for (int i = 0; i < ROW_SIZE; i++) {
					assertElement("cacheWrite", row, i, stored[row * ROW_SIZE + i]);
				}
			}

			for (int row = 0; row < ROWS; row++) {
				for (int i = 0; i < ROW_SIZE; i++) {
					assertElement("cacheRead", row, i, rows[row][i]);
				}
			}
		}
	}

	/**
	 * Fails unless {@code actual} is the value written to element {@code i} of row {@code row}.
	 *
	 * @param operation the operation whose result is checked
	 * @param row       the row
	 * @param i         the element within the row
	 * @param actual    the value the operation left or returned for that element
	 */
	private void assertElement(String operation, int row, int i, double actual) {
		if (actual != written(row, i)) {
			Assert.fail(operation + ": row " + row + " element " + i + " (cache element "
					+ (row * (long) ROW_SIZE + i) + ") is " + actual + ", expected " + written(row, i));
		}
	}
}
