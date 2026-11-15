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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Operations written for one item applied to a fixed-count batch of items: a subset with
 * fewer dimensions than its input is taken from every item, and the constructors of items
 * ({@code pair}, {@code vector}, {@code ray}, {@code rgb}) build a batch from batched components,
 * repeating a single shared component for every item.
 */
public class BatchItemTests extends TestSuiteBase implements RGBFeatures {
	/** The number of items in each batch. */
	private static final int ITEMS = 4;

	/**
	 * Returns an {@code (ITEMS, width)} batch whose element {@code (i, j)} is
	 * {@code i * width + j}.
	 *
	 * @param width the size of each item
	 * @return the batch
	 */
	private CollectionProducer batch(int width) {
		return integers(0, ITEMS * width).reshape(shape(ITEMS, width).traverse(1));
	}

	/** A {@code (1)} subset of an {@code (n, 2)} input is the {@code (n, 1)} column of every row. */
	@Test(timeout = 60000)
	public void lowerRankSubsetReadsEveryItem() {
		CollectionProducer second = subset(shape(1), batch(2), 1);
		assertEquals(2, second.getShape().getDimensions());
		assertEquals(ITEMS, second.getShape().getCountLong());

		PackedCollection result = second.evaluate();
		for (int i = 0; i < ITEMS; i++) {
			assertEquals(2 * i + 1, result.toDouble(i));
		}
	}

	/** A {@code (3)} subset of an {@code (n, 6)} input is the {@code (n, 3)} tail of every row. */
	@Test(timeout = 60000)
	public void lowerRankSubsetOfWiderItems() {
		PackedCollection result = subset(shape(3), batch(6), 3).evaluate();
		assertEquals(ITEMS * 3, result.getShape().getTotalSize());

		for (int i = 0; i < ITEMS; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals(6 * i + 3 + j, result.toDouble(3 * i + j));
			}
		}
	}

	/** Components taken from a batch of vectors rebuild the batch, reordered by {@code vector}. */
	@Test(timeout = 60000)
	public void vectorOfBatchedComponents() {
		CollectionProducer v = batch(3);
		CollectionProducer reversed = vector(subset(shape(1), v, 2), subset(shape(1), v, 1),
				subset(shape(1), v, 0));
		assertEquals(ITEMS, reversed.getShape().getCountLong());

		PackedCollection result = reversed.evaluate();
		for (int i = 0; i < ITEMS; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals(3 * i + 2 - j, result.toDouble(3 * i + j));
			}
		}
	}

	/** A ray built from one shared origin and a batch of directions repeats the origin. */
	@Test(timeout = 60000)
	public void rayRepeatsSharedOrigin() {
		CollectionProducer rays = ray(vector(-1.0, -2.0, -3.0), batch(3));
		assertEquals(ITEMS, rays.getShape().getCountLong());

		PackedCollection result = rays.evaluate();
		for (int i = 0; i < ITEMS; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals(-(j + 1), result.toDouble(6 * i + j));
				assertEquals(3 * i + j, result.toDouble(6 * i + 3 + j));
			}
		}
	}

	/** A ray built from a two-level batch of directions repeats a shared origin over both levels. */
	@Test(timeout = 60000)
	public void rayRepeatsSharedOriginOverNestedBatch() {
		CollectionProducer directions = integers(0, 2 * ITEMS * 3).reshape(shape(2, ITEMS, 3).traverse(2));
		CollectionProducer rays = ray(vector(-1.0, -2.0, -3.0), directions);
		assertEquals(3, rays.getShape().getDimensions());
		assertEquals(2 * ITEMS, rays.getShape().getCountLong());

		PackedCollection result = rays.evaluate();
		for (int i = 0; i < 2 * ITEMS; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals(-(j + 1), result.toDouble(6 * i + j));
				assertEquals(3 * i + j, result.toDouble(6 * i + 3 + j));
			}
		}
	}

	/** Components taken from a batch of pairs rebuild the batch, swapped by {@code pair}. */
	@Test(timeout = 60000)
	public void pairOfBatchedComponents() {
		CollectionProducer p = batch(2);
		CollectionProducer swapped = pair(subset(shape(1), p, 1), subset(shape(1), p, 0));
		assertEquals(ITEMS, swapped.getShape().getCountLong());

		PackedCollection result = swapped.evaluate();
		for (int i = 0; i < ITEMS; i++) {
			assertEquals(2 * i + 1, result.toDouble(2 * i));
			assertEquals(2 * i, result.toDouble(2 * i + 1));
		}
	}

	/** A color built from batched channels and one shared channel repeats the shared channel. */
	@Test(timeout = 60000)
	public void rgbRepeatsSharedChannel() {
		CollectionProducer channels = batch(2);
		CollectionProducer colors = rgb(subset(shape(1), channels, 0), c(0.5),
				subset(shape(1), channels, 1));
		assertEquals(ITEMS, colors.getShape().getCountLong());

		PackedCollection result = colors.evaluate();
		for (int i = 0; i < ITEMS; i++) {
			assertEquals(2 * i, result.toDouble(3 * i));
			assertEquals(0.5, result.toDouble(3 * i + 1));
			assertEquals(2 * i + 1, result.toDouble(3 * i + 2));
		}
	}

	/** Every vector of a batch is multiplied by its own scalar. */
	@Test(timeout = 60000)
	public void scaleMultipliesEachVector() {
		CollectionProducer scalars = subset(shape(1), batch(2), 0);
		PackedCollection result = scale(batch(3), scalars).evaluate();

		for (int i = 0; i < ITEMS; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals((3 * i + j) * (2 * i), result.toDouble(3 * i + j));
			}
		}
	}
}
