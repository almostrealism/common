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

package org.almostrealism.hardware.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the literal varargs {@code setMem} surface on
 * {@link org.almostrealism.hardware.MemoryData}: writes resolve the delegate
 * chain to the root reservation, land at the correct positions through views,
 * and reject writes extending past the root.
 */
public class MemoryDataViewWriteTest extends TestSuiteBase {

	/** Number of elements in the root collections. */
	private static final int SIZE = 32;

	/**
	 * Writes through the root land at the written positions.
	 */
	@Test(timeout = 60000)
	public void rootWrites() {
		PackedCollection root = new PackedCollection(shape(SIZE));
		root.setMem(new double[] { 1.0, 2.0, 3.0 });
		root.setMem(SIZE - 1, 9.0);

		Assert.assertEquals(1.0, root.toDouble(0), 0.0);
		Assert.assertEquals(2.0, root.toDouble(1), 0.0);
		Assert.assertEquals(3.0, root.toDouble(2), 0.0);
		Assert.assertEquals(9.0, root.toDouble(SIZE - 1), 0.0);
	}

	/**
	 * Writes through a range view resolve the delegate chain and land at the
	 * view's offset within the root, including through a view of a view.
	 */
	@Test(timeout = 60000)
	public void delegatedViewWrites() {
		PackedCollection root = new PackedCollection(shape(SIZE));

		PackedCollection view = root.range(new TraversalPolicy(8), 10);
		view.setMem(new double[] { 5.0, 6.0 });
		view.setMem(7, 7.0);

		Assert.assertEquals(5.0, root.toDouble(10), 0.0);
		Assert.assertEquals(6.0, root.toDouble(11), 0.0);
		Assert.assertEquals(7.0, root.toDouble(17), 0.0);

		PackedCollection nested = view.range(new TraversalPolicy(4), 2);
		nested.setMem(1, 8.0);
		Assert.assertEquals(8.0, root.toDouble(13), 0.0);
	}

	/**
	 * A write extending past the root reservation is rejected, whether it is an
	 * indexed write beyond the last element or a whole-content write longer than
	 * the reservation.
	 */
	@Test(timeout = 60000)
	public void writePastRootRejected() {
		PackedCollection root = new PackedCollection(shape(SIZE));

		try {
			root.setMem(SIZE, 1.0);
			Assert.fail("Indexed write past the root should be rejected");
		} catch (IllegalArgumentException e) {
			// Expected
		}

		PackedCollection view = root.range(new TraversalPolicy(2), SIZE - 2);

		try {
			view.setMem(new double[] { 1.0, 2.0, 3.0 });
			Assert.fail("Whole-content write past the root should be rejected");
		} catch (IllegalArgumentException e) {
			// Expected
		}
	}
}
