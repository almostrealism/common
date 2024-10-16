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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class CollectionTraversalTests implements TestFeatures {
	@Test
	public void stride1() {
		PackedCollection<?> root = new PackedCollection<>(shape(4, 4)).randFill();

		TraversalPolicy policy = shape(4, 2)
				.withRate(1, 2, 1);
		assertEquals(4, policy.inputLength(0));
		assertEquals(4, policy.inputLength(1));
		assertEquals(16, policy.getTotalInputSize());

		PackedCollection<?> strided = root.range(policy);

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 2; j++) {
				log("index = " + policy.index(i, j));
				log(root.valueAt(i, 2 * j) + " vs " + strided.valueAt(i, j));
				assertEquals(root.valueAt(i, 2 * j), strided.valueAt(i, j));
			}
		}
	}

	@Test
	public void stride2() {
		PackedCollection<?> root = new PackedCollection<>(shape(4, 4)).randFill();

		TraversalPolicy policy = shape(2, 4)
				.withRate(0, 2, 1);
		assertEquals(4, policy.inputLength(0));
		assertEquals(4, policy.inputLength(1));

		PackedCollection<?> strided = root.range(policy);

		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 4; j++) {
				log("index = " + policy.index(i, j));
				log(root.valueAt(2 * i, j) + " vs " + strided.valueAt(i, j));
				assertEquals(root.valueAt(2 * i, j), strided.valueAt(i, j));
			}
		}
	}

	@Test
	public void stride3() {
		PackedCollection<?> root = new PackedCollection<>(shape(4, 4)).randFill();

		TraversalPolicy policy = shape(4, 8)
				.withRate(1, 1, 2);

		assertEquals(4, policy.inputLength(0));
		assertEquals(4, policy.inputLength(1));

		PackedCollection<?> strided = root.range(policy);

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 8; j++) {
				log("index = " + policy.index(i, j));
				log(root.valueAt(i, j / 2) + " vs " + strided.valueAt(i, j));
				assertEquals(root.valueAt(i, j / 2), strided.valueAt(i, j));
			}
		}
	}

	@Test
	public void stride4() {
		PackedCollection<?> root = new PackedCollection<>(shape(4, 4)).randFill();

		TraversalPolicy policy = shape(8, 4)
				.withRate(0, 1, 2);

		assertEquals(4, policy.inputLength(0));
		assertEquals(4, policy.inputLength(1));

		PackedCollection<?> strided = root.range(policy);

		for (int i = 0; i < 8; i++) {
			for (int j = 0; j < 4; j++) {
				log("index = " + policy.index(i, j));
				log(root.valueAt(i / 2, j) + " vs " + strided.valueAt(i, j));
				assertEquals(root.valueAt(i / 2, j), strided.valueAt(i, j));
			}
		}
	}

	@Test
	public void stride5() {
		int m = 2;
		int n = 2;
		int p = 4;

		PackedCollection<?> root = new PackedCollection<>(shape(m, n)).randFill();

		TraversalPolicy policy = shape(m, p)
				.withRate(1, n, p);

		assertEquals(m, policy.inputLength(0));
		assertEquals(n, policy.inputLength(1));

		PackedCollection<?> strided = root.range(policy);

		for (int i = 0; i < m; i++) {
			for (int j = 0; j < p; j++) {
				log("index = " + policy.index(i, j));
				assertEquals(policy.index(i, j), policy.index(e(i), e(j)).intValue().getAsInt());

				log(root.valueAt(i, 0) + " vs " + strided.valueAt(i, j));
				assertEquals(root.valueAt(i, 0), strided.valueAt(i, j));
			}
		}
	}

	@Test
	public void stride6() {
		int m = 4;
		int n = 2;
		int p = 3;

		PackedCollection<?> root = new PackedCollection<>(shape(m, n)).randFill();

		TraversalPolicy policy = shape(m, p)
				.withRate(1, n, p);

		assertEquals(m, policy.inputLength(0));
		assertEquals(n, policy.inputLength(1));

		PackedCollection<?> strided = root.range(policy);

		for (int i = 0; i < m; i++) {
			for (int j = 0; j < p; j++) {
				log("index = " + policy.index(i, j));
				log(root.valueAt(i, 0) + " vs " + strided.valueAt(i, j));
				assertEquals(root.valueAt(i, 0), strided.valueAt(i, j));
			}
		}
	}
}
