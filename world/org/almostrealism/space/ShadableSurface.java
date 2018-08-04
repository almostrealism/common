/*
 * Copyright 2017 Michael Murray
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

import org.almostrealism.algebra.Intersectable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.ColorProducer;
import org.almostrealism.color.ShadableCurve;
import org.almostrealism.uml.ModelEntity;

/**
 * The {@link ShadableSurface} interface is implemented by any 3d object which may be
 * intersected by a 3d ray. These objects must supply methods for calculating
 * ray-surface intersections.
 */
@ModelEntity
public interface ShadableSurface extends ShadableCurve, Intersectable<ShadableIntersection, Scalar> {
	/**
	 * Returns true if the front side of this Surface object should be shaded.
	 * The "front side" is the side that the Vector object returned by the
	 * {@link #getNormalAt(Vector)} method for this Surface object points
	 * outward from.
	 */
	boolean getShadeFront();
	
	/**
	 * Returns true if the back side of this Surface object should be shaded.
	 * The "back side" is the side that the vector opposite the Vector object
	 * returned by the {@link #getNormalAt(Vector)} method for this Surface
	 * points outward from.
	 */
	boolean getShadeBack();
	
	/**
	 * Returns a {@link ColorProducer} that operates on a {@link Vector},
	 * indicating the location where color is to be evaluated, returning an
	 * {@link org.almostrealism.color.RGB}.
	 *
	 * @deprecated  Replaced by {@link #evaluate(Object[])}
	 */
	@Deprecated
	ColorProducer getColorAt();

	/**
	 * Return a {@link BoundingSolid} that represents the minimum bounded
	 * solid that contains this {@link ShadableSurface}.
	 * Returns null for certain types of {@link ShadableSurface} objects
	 * which do not have measurable bounds. E.g. Plane, Polynomial.
	 */
	BoundingSolid calculateBoundingSolid();
}
