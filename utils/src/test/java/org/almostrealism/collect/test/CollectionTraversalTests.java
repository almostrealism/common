/*
 * Copyright 2025 Michael Murray
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
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class CollectionTraversalTests implements TestFeatures {
	@Test
	public void stride1() {
		PackedCollection root = new PackedCollection(shape(4, 4)).randFill();

		TraversalPolicy policy = shape(4, 2)
				.withRate(1, 2, 1);
		assertEquals(4, policy.inputLength(0));
		assertEquals(4, policy.inputLength(1));
		assertEquals(16, policy.getTotalInputSize());

		PackedCollection strided = root.range(policy);

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
		PackedCollection root = new PackedCollection(shape(4, 4)).randFill();

		TraversalPolicy policy = shape(2, 4)
				.withRate(0, 2, 1);
		assertEquals(4, policy.inputLength(0));
		assertEquals(4, policy.inputLength(1));

		PackedCollection strided = root.range(policy);

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
		PackedCollection root = new PackedCollection(shape(4, 4)).randFill();

		TraversalPolicy policy = shape(4, 8)
				.withRate(1, 1, 2);

		assertEquals(4, policy.inputLength(0));
		assertEquals(4, policy.inputLength(1));

		PackedCollection strided = root.range(policy);

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
		PackedCollection root = new PackedCollection(shape(4, 4)).randFill();

		TraversalPolicy policy = shape(8, 4)
				.withRate(0, 1, 2);

		assertEquals(4, policy.inputLength(0));
		assertEquals(4, policy.inputLength(1));

		PackedCollection strided = root.range(policy);

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

		PackedCollection root = new PackedCollection(shape(m, n)).randFill();

		TraversalPolicy policy = shape(m, p)
				.withRate(1, n, p);

		assertEquals(m, policy.inputLength(0));
		assertEquals(n, policy.inputLength(1));

		PackedCollection strided = root.range(policy);

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

		PackedCollection root = new PackedCollection(shape(m, n)).randFill();

		TraversalPolicy policy = shape(m, p)
				.withRate(1, n, p);

		assertEquals(m, policy.inputLength(0));
		assertEquals(n, policy.inputLength(1));

		PackedCollection strided = root.range(policy);

		double values[] = strided.doubleStream().toArray();
		int idx = 0;

		for (int i = 0; i < m; i++) {
			for (int j = 0; j < p; j++) {
				log("index = " + policy.index(i, j));
				log(root.valueAt(i, 0) + " vs " + strided.valueAt(i, j));
				assertEquals(root.valueAt(i, 0), strided.valueAt(i, j));
				assertEquals(strided.valueAt(i, j), values[idx++]);
			}
		}
	}

	@Test
	public void stride7() {
		TraversalPolicy left = shape(2, 1)
				.repeat(1, 2);
		TraversalPolicy right = shape(1, 2)
				.repeat(0, 2);

		log("left = " + left.inputShape());
		log("right = " + right.inputShape());

		StringBuilder result = new StringBuilder();

		left.inputPositions()
				.map(Arrays::toString)
				.forEach(result::append);
		log(result);
		log("---");
		Assert.assertEquals("[0, 0][0, 0][1, 0][1, 0]", result.toString());

		result = new StringBuilder();
		right.inputPositions()
				.map(Arrays::toString)
				.forEach(result::append);
		log(result);
		Assert.assertEquals("[0, 0][0, 1][0, 0][0, 1]", result.toString());
	}

	@Test
	public void stride8() {
		TraversalPolicy policy = shape(2, 4)
				.withRate(0, 3, 2)
				.withRate(1, 2, 4);

		log("inputShape = " + policy.inputShape());
		assertEquals(3, policy.inputLength(0));
		assertEquals(2, policy.inputLength(1));

		log("index = " + policy.index(2, 0));
		log(Arrays.toString(policy.position(4)));

		policy.inputPositions()
				.map(Arrays::toString)
				.forEach(System.out::println);
	}

	@Test
	public void stride9() {
		int bs = 1;
		int r = 3;
		int c1 = 2;
		int c2 = 3;

		PackedCollection root = new PackedCollection(shape(bs, r, c1)).randFill();

		TraversalPolicy policy = shape(bs, c1, c2)
							.withRate(2, 1, c2);

		StringBuilder result = new StringBuilder();
		policy.indices()
				.mapToObj(root.getShape()::position)
				.map(Arrays::toString)
				.forEach(result::append);
		log(result);
		Assert.assertEquals("[0, 0, 0][0, 0, 0][0, 0, 0][0, 0, 1][0, 0, 1][0, 0, 1]", result.toString());
	}
}
