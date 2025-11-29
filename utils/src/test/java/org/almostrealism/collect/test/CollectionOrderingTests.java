/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.collect.test;

import io.almostrealism.collect.RepeatTraversalOrdering;
import org.almostrealism.collect.ExplicitIndexTraversalOrdering;
import org.almostrealism.collect.IndexMaskTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class CollectionOrderingTests implements TestFeatures {
	@Test
	public void repeatOrdering() {
		if (skipKnownIssues) return;

		PackedCollection root = pack(2.0, 3.0, 1.0);
		PackedCollection repeated = new PackedCollection(shape(4, 3), 1,
										root, 0, new RepeatTraversalOrdering(3));
		repeated.print();

		assertEquals(2.0, repeated.valueAt(0, 0));
		assertEquals(3.0, repeated.valueAt(0, 1));
		assertEquals(1.0, repeated.valueAt(0, 2));
		assertEquals(2.0, repeated.valueAt(1, 0));
		assertEquals(3.0, repeated.valueAt(1, 1));
		assertEquals(1.0, repeated.valueAt(1, 2));
	}

	@Test
	public void repeatOrderingProduct() {
		PackedCollection root = pack(2.0, 3.0, 1.0);
		PackedCollection repeated = new PackedCollection(shape(4, 3), 1,
				root, 0, new RepeatTraversalOrdering(3));

		verboseLog(() -> {
			PackedCollection product = c(2).multiply(cp(repeated)).evaluate();
			product.print();

			assertEquals(4.0, product.valueAt(0, 0));
			assertEquals(6.0, product.valueAt(0, 1));
			assertEquals(2.0, product.valueAt(0, 2));
			assertEquals(4.0, product.valueAt(3, 0));
			assertEquals(6.0, product.valueAt(3, 1));
			assertEquals(2.0, product.valueAt(3, 2));
		});
	}

	@Test
	public void compactOrdering() {
		if (skipKnownIssues) return;

		PackedCollection values = pack(2.0, 3.0);

		ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(pack(0, -1, -1, 1));
		PackedCollection compact = new PackedCollection(shape(2, 2), 1, values, 0, order);

		compact.print();

		assertEquals(2.0, compact.valueAt(0, 0));
		assertEquals(0.0, compact.valueAt(0, 1));
		assertEquals(0.0, compact.valueAt(1, 0));
		assertEquals(3.0, compact.valueAt(1, 1));
	}

	@Test
	public void maskOrdering() {
		if (skipKnownIssues) return;

		PackedCollection values = pack(2.0, 3.0);
		PackedCollection indices = pack(0, 3);

		IndexMaskTraversalOrdering order = new IndexMaskTraversalOrdering(indices);
		PackedCollection compact = new PackedCollection(shape(2, 2), 1, values, 0, order);

		compact.print();

		assertEquals(2.0, compact.valueAt(0, 0));
		assertEquals(0.0, compact.valueAt(0, 1));
		assertEquals(0.0, compact.valueAt(1, 0));
		assertEquals(3.0, compact.valueAt(1, 1));
	}
}
