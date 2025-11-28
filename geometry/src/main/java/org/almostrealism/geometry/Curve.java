/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.collect.PackedCollection;

import io.almostrealism.relation.Node;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.Gradient;

/**
 * A parametric curve interface that maps 3D points in space to values of type {@code T}.
 * Unlike a {@link Gradient}, which specifically provides normal vectors, a {@link Curve}
 * can provide an arbitrary type of value for every point in space.
 *
 * <p>This interface is useful for defining continuous fields over 3D space,
 * such as color gradients, density fields, or any other spatially-varying property.</p>
 *
 * @param <T> the type of value returned at each point in space
 * @author  Michael Murray
 * @see Gradient
 * @see CurveAdapter
 */
public interface Curve<T> extends Gradient<T>, Node {
	/**
	 * Returns the value of this curve at the specified point in 3D space.
	 *
	 * @param point the position in 3D space at which to evaluate the curve
	 * @return a {@link Producer} that produces the value at the specified point
	 */
	Producer<T> getValueAt(Producer<PackedCollection> point);
}
