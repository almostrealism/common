/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.space;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.geometry.Curve;
import org.almostrealism.util.Producer;

/**
 * A {@link Volume} represents a region of space in three dimensions
 * bounded by a {@link Curve}. In combination with an instance of
 * {@link org.almostrealism.physics.Absorber}, a {@link Volume}
 * defines a solid object.
 * 
 * @author  Michael Murray
 */
public interface Volume<T> extends Curve<T> {
	/**
	 * Checks if a point is within this volume.
	 * 
	 * @param x  {x, y, z} - The point in space to test.
	 * @return  True if the point is within this volume, false otherwise.
	 */
	boolean inside(Producer<Vector> x);
	
	/**
	 * Calculates the distance along the line defined by the specified position
	 * and direction vectors that the line intersects with this Volume. This is
	 * a maximum distance that the volume can garuentee that intersection does
	 * not occur. If intersection cannot be calculated, zero should be returned.
	 * 
	 * @param p  The position.
	 * @param d  The direction.
	 *
	 * @return  The distance before intersection occurs.
	 */
	// TODO  Replace with intersectable
	double intersect(Vector p, Vector d);
	
	/**
	 * Returns 2D coordinates on the surface of this volume at the specified point
	 * in 3D.
	 * 
	 * @param xyz  {x, y, z} - Position in spatial coordinates.
	 * @return  {u, v} - Position in surface coordinates (u,v between 0.0 and 1.0).
	 */
	double[] getSurfaceCoords(Producer<Vector> xyz);
	
	/**
	 * Returns 3D coordinates on the surface of this volume at the specified point
	 * in 2D surface coordinates.
	 * 
	 * @param uv  {u, v} - Position in surface coordinates (u,v between 0.0 and 1.0).
	 * @return  {x, y, z} - Position in spatial coordinates.
	 */
	double[] getSpatialCoords(double uv[]);
}
