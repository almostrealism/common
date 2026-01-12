/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

import org.almostrealism.algebra.Vector;

/**
 * A concrete implementation of {@link Spatial} representing a positioned object
 * in 3D space.
 *
 * <p>{@code SpatialElement} serves as the base class for all positioned objects
 * in the spatial visualization system. It provides mutable position storage and
 * can be extended to add additional properties such as values, groups, or
 * rendering metadata.</p>
 *
 * <p>The coordinate system typically uses:</p>
 * <ul>
 *   <li><b>X-axis</b>: Time dimension (scaled by {@link TemporalSpatialContext})</li>
 *   <li><b>Y-axis</b>: Frequency or channel dimension</li>
 *   <li><b>Z-axis</b>: Layer dimension for visual separation</li>
 * </ul>
 *
 * @see Spatial
 * @see SpatialValue
 * @see SpatialGroup
 * @see TemporalSpatialContext
 */
public class SpatialElement implements Spatial {
	private Vector position;

	/**
	 * Creates a new {@code SpatialElement} with no initial position.
	 * The position should be set via {@link #setPosition(Vector)} before use.
	 */
	public SpatialElement() { }

	/**
	 * Creates a new {@code SpatialElement} at the specified position.
	 *
	 * @param position the 3D position of this element
	 */
	public SpatialElement(Vector position) {
		this.position = position;
	}

	/**
	 * Returns the position of this element in 3D space.
	 *
	 * @return the position vector, or {@code null} if not set
	 */
	@Override
	public Vector getPosition() { return position; }

	/**
	 * Sets the position of this element in 3D space.
	 *
	 * @param position the new position vector
	 */
	public void setPosition(Vector position) { this.position = position; }
}
