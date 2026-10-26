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

package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Tensor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link Tensor#getTotalSize()}.
 *
 * <p>{@link Tensor} stores its data in a sparse tree of {@link java.util.ArrayList}s.
 * Inserting an element at a high index within an otherwise-empty sub-list grows that
 * list with {@code Leaf(null)} placeholders up to the target index (see
 * {@code Tensor.insert}). Those placeholders are structural padding, not elements the
 * caller inserted, so {@link Tensor#getTotalSize()} — documented as counting "all
 * non-null elements" — must not count them.</p>
 */
public class TensorTest extends TestSuiteBase {

	/**
	 * The three-dimensional example from {@link Tensor}'s own class javadoc: two
	 * elements are inserted, one of them at {@code (1, 2, 3)}, and the documentation
	 * states {@code getTotalSize()} is {@code 2}. The insert at index 3 of a fresh
	 * sub-list pads it with three {@code Leaf(null)} entries, which must not be counted.
	 */
	@Test(timeout = 60000)
	public void getTotalSizeMatchesJavadocExample() {
		Tensor<String> tensor3D = new Tensor<>();
		tensor3D.insert("value", 0, 0, 0);
		tensor3D.insert("another", 1, 2, 3);

		Assert.assertEquals(3, tensor3D.getDimensions());
		Assert.assertEquals(2, tensor3D.getTotalSize());
	}

	/**
	 * A minimal two-dimensional case: two elements are inserted into the same row, the
	 * second at column 3. The gap between them is filled with {@code Leaf(null)} padding
	 * that {@link Tensor#getTotalSize()} must not count as elements.
	 */
	@Test(timeout = 60000)
	public void getTotalSizeIgnoresPaddingGaps() {
		Tensor<String> tensor = new Tensor<>();
		tensor.insert("a", 0, 0);
		tensor.insert("b", 0, 3);

		Assert.assertEquals("a", tensor.get(0, 0));
		Assert.assertEquals("b", tensor.get(0, 3));
		Assert.assertNull(tensor.get(0, 1));
		Assert.assertEquals(2, tensor.getTotalSize());
	}
}
