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

package org.almostrealism.geometry;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * An {@link ImplicitSurface} whose function is a signed distance: the distance from a
 * position to the nearest point of the surface, negated inside it. Its
 * {@link #lipschitzBound()} is therefore {@code 1}, and a ray marcher can step by the value
 * itself.
 *
 * <p>The distance is the only abstract operation, so any distance that can be written with
 * producers is a signed distance function, and a lambda over the position is enough:</p>
 * <pre>{@code
 * // A unit sphere at the origin
 * SignedDistanceFunction sphere = p -> length(p).subtract(c(1.0));
 * }</pre>
 *
 * <p>Its normals are estimated from the gradient of the distance (see
 * {@link ImplicitSurface#normal(Producer)}); a surface whose normal is known analytically
 * implements this interface and overrides {@link #normal(Producer)}.</p>
 *
 * <p>The distance must accept an {@code (n, 3)} batch of positions and produce the
 * {@code (n, 1)} batch of distances, as well as a single {@code (3)} position. Most
 * producer operations do this without special care; a constant {@code (3)} vector combined
 * with the positions is repeated for every position with
 * {@link #repeatItems(io.almostrealism.collect.TraversalPolicy, Producer)}.</p>
 *
 * @author Michael Murray
 */
@FunctionalInterface
public interface SignedDistanceFunction extends ImplicitSurface {
	/**
	 * Returns the signed distance from the given position to the surface.
	 *
	 * @param position a {@code (3)} position, or an {@code (n, 3)} batch of positions
	 * @return the {@code (1)} distance, or the {@code (n, 1)} batch of distances
	 */
	@Override
	CollectionProducer value(Producer<PackedCollection> position);

	/**
	 * Returns {@code 1}, the Lipschitz bound of every signed distance.
	 *
	 * @return a producer of the {@code (1)} bound
	 */
	@Override
	default CollectionProducer lipschitzBound() {
		return c(1.0);
	}
}
