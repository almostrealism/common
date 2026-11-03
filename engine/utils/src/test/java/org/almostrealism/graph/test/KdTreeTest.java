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

package org.almostrealism.graph.test;

import org.almostrealism.graph.KdTree;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Random;

/**
 * Tests for {@link KdTree}, focused on the bounded-size ("sizeLimit") eviction
 * path implemented by {@code removeOld()}.
 *
 * <p>When a {@link KdTree} is constructed with a non-null {@code sizeLimit}, the
 * oldest point is discarded each time the limit is exceeded. These tests verify
 * that eviction keeps {@link KdTree#size()} accurate and does not fail when the
 * tree is still a single leaf.</p>
 */
public class KdTreeTest extends TestSuiteBase {

	/**
	 * When the number of retained points stays below the leaf bucket size, the
	 * tree is never split and eviction must operate on the root leaf directly.
	 * This exercises the case where the node holding the evicted point is the
	 * root itself.
	 */
	@Test(timeout = 30000)
	public void evictionOnSingleLeafTree() {
		KdTree.SqrEuclid<Integer> tree = new KdTree.SqrEuclid<>(3, 2);
		tree.addPoint(new double[] {0.0, 0.0, 0.0}, 0);
		tree.addPoint(new double[] {10.0, 10.0, 10.0}, 1);
		// The third addition exceeds the size limit and evicts the oldest point.
		tree.addPoint(new double[] {20.0, 20.0, 20.0}, 2);

		Assert.assertEquals(2, tree.size());

		// The oldest point (index 0 at the origin) must have been evicted, so a
		// query at the origin should return one of the two retained points, not
		// the evicted one.
		List<KdTree.Entry<Integer>> nearest = tree.nearestNeighbor(new double[] {0.0, 0.0, 0.0}, 1, true);
		Assert.assertEquals(1, nearest.size());
		Assert.assertNotEquals(Integer.valueOf(0), nearest.get(0).value);
	}

	/**
	 * After the tree has split into stem and leaf nodes, repeated eviction must
	 * still keep {@link KdTree#size()} pinned at the configured limit rather than
	 * letting the root's count grow with every insertion.
	 */
	@Test(timeout = 30000)
	public void evictionKeepsSizeBounded() {
		int limit = 30;
		KdTree.SqrEuclid<Integer> tree = new KdTree.SqrEuclid<>(3, limit);

		Random random = new Random(1);
		for (int i = 0; i < 100; i++) {
			tree.addPoint(new double[] {
					random.nextInt(100), random.nextInt(100), random.nextInt(100)}, i);
		}

		Assert.assertEquals(limit, tree.size());
	}
}
