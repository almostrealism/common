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

package org.almostrealism.space.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.space.SpacePartition;
import org.almostrealism.space.Triangle;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link SpacePartition}, verifying the tree-loaded state
 * transition and BSP construction over triangle surfaces.
 */
public class SpacePartitionTest extends TestSuiteBase {

	/**
	 * Builds a partition containing two non-degenerate triangles.
	 *
	 * @return the populated partition
	 */
	private SpacePartition<Triangle> twoTriangles() {
		SpacePartition<Triangle> partition = new SpacePartition<>();
		partition.addSurface(new Triangle(new Vector(0.0, 0.0, 0.0),
				new Vector(1.0, 0.0, 0.0), new Vector(0.0, 1.0, 0.0)));
		partition.addSurface(new Triangle(new Vector(2.0, 0.0, 0.0),
				new Vector(3.0, 0.0, 0.0), new Vector(2.0, 1.0, 0.0)));
		return partition;
	}

	/** A partition reports no tree until one is explicitly built. */
	@Test(timeout = 30000)
	public void treeNotLoadedInitially() {
		SpacePartition<Triangle> partition = twoTriangles();
		Assert.assertFalse(partition.isTreeLoaded());
	}

	/** loadTree builds the BSP tree, flipping the loaded state to true. */
	@Test(timeout = 30000)
	public void loadTreeMarksLoaded() {
		SpacePartition<Triangle> partition = twoTriangles();
		partition.loadTree();
		Assert.assertTrue(partition.isTreeLoaded());
	}

	/** loadTree(count) builds a tree using an explicit surface count. */
	@Test(timeout = 30000)
	public void loadTreeWithExplicitCount() {
		SpacePartition<Triangle> partition = twoTriangles();
		partition.loadTree(2);
		Assert.assertTrue(partition.isTreeLoaded());
	}

	/**
	 * After the tree is loaded, intersectAt traverses the (currently unimplemented)
	 * node structure and returns null.
	 */
	@Test(timeout = 30000)
	public void intersectAtAfterLoadReturnsNull() {
		SpacePartition<Triangle> partition = twoTriangles();
		partition.loadTree();
		ContinuousField field = partition.intersectAt(ray(0.0, 0.0, 5.0, 0.0, 0.0, -1.0));
		Assert.assertNull(field);
	}
}
