/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.algebra.Vector;

/**
 * Base interface for objects that have a position in 3D space.
 *
 * <p>The {@code Spatial} interface provides a contract for any object that
 * can be positioned within a three-dimensional coordinate system. It serves
 * as the foundation for the spatial hierarchy used in audio/visual data
 * visualization within the Rings framework.</p>
 *
 * <p>Implementations include:</p>
 * <ul>
 *   <li>{@link SpatialElement} - A concrete positioned object</li>
 *   <li>{@link SpatialValue} - A positioned object with an associated numeric value</li>
 *   <li>{@link SpatialGroup} - A container for grouping multiple spatial elements</li>
 * </ul>
 *
 * @see SpatialElement
 * @see SpatialValue
 * @see SpatialGroup
 */
public interface Spatial {

	/**
	 * Returns the position of this spatial object in 3D space.
	 *
	 * @return a {@link Vector} representing the (x, y, z) coordinates of this object
	 */
	Vector getPosition();
}
