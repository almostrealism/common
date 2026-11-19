/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.space;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.mesh.TriangleFeatures;

/**
 * Feature interface for building mesh geometry as {@link CollectionProducer} graphs.
 *
 * <p>{@code MeshFeatures} extends {@link TriangleFeatures} so that a type which needs to
 * describe mesh buffers can <em>implement</em> this mixin and compose the producer graphs
 * directly. Like every {@code Features} interface, its methods are stateless {@code default}
 * methods that assemble computation graphs; they never call {@code evaluate()}. Materializing a
 * buffer into device memory is the responsibility of the top of the call stack (a test, a
 * pipeline boundary), not of these builders.</p>
 *
 * @author  Michael Murray
 * @see TriangleFeatures
 * @see MeshData
 */
public interface MeshFeatures extends TriangleFeatures {

	/**
	 * Tessellates a regular grid height field into a triangle buffer producer ready for the
	 * {@link MeshData} ray intersection path, building the whole buffer in a single kernel so
	 * the surface can be rebuilt each frame without a host loop.
	 *
	 * <p>Height is the Y axis and the grid lies in the XZ plane; see
	 * {@link TriangleFeatures#regularGridSurfacePoints} for the node layout and winding. The
	 * producer evaluates to {@code 2*(nx-1)*(ny-1)} triangles, each shaped {@code (4, 3)}.</p>
	 *
	 * @param heights  the height field, shaped {@code (nx, ny)} in row-major order
	 * @param nx       the number of grid nodes along X
	 * @param ny       the number of grid nodes along Z
	 * @param origin   the position of node {@code (0, 0)}; its Y is added to every height
	 * @param spacingX the node spacing along X
	 * @param spacingZ the node spacing along Z
	 * @return a producer for the tessellated triangle buffer
	 */
	default CollectionProducer gridSurface(Producer<PackedCollection> heights, int nx, int ny,
										   Vector origin, double spacingX, double spacingZ) {
		return triangle(regularGridSurfacePoints(heights, nx, ny,
				origin.getX(), origin.getY(), origin.getZ(), spacingX, spacingZ));
	}

	/**
	 * Provides a stateless {@link MeshFeatures} instance for callers that are not themselves
	 * {@code MeshFeatures} implementors.
	 *
	 * @return a {@link MeshFeatures} instance
	 */
	static MeshFeatures getInstance() {
		return new MeshFeatures() { };
	}
}
