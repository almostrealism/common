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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.util.TestFeatures;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared fixture helper for grid-surface tests: decomposing a flat row-major grid index into
 * {@code (i, j)} node coordinates as Producer graphs.
 */
public interface GridIndexFeatures extends TestFeatures {
	/**
	 * Decomposes a flat row-major grid index into {@code (i, j)} node coordinates as Producer
	 * graphs, indexing the grid with {@code integers()} rather than a host loop, so no
	 * host-computed values are staged.
	 *
	 * @param nx the number of grid nodes along X
	 * @param ny the number of grid nodes along Z
	 * @return a two-element array {@code {i, j}}, each a producer over the {@code nx*ny} flat index
	 */
	default CollectionProducer[] gridIndices(int nx, int ny) {
		CollectionProducer index = integers(0, nx * ny);
		CollectionProducer i = floor(index.divide((double) ny));
		CollectionProducer j = index.subtract(i.multiply((double) ny));
		return new CollectionProducer[] { i, j };
	}

	/**
	 * Returns a triangle's vertex indices as a set, since a format or tessellation may
	 * change the winding.
	 *
	 * @param triangle the vertex indices
	 * @return the set of indices
	 */
	default Set<Integer> corners(int[] triangle) {
		return new HashSet<>(Arrays.asList(triangle[0], triangle[1], triangle[2]));
	}
}
