/*
 * Copyright 2024 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial;

/**
 * A factory interface for generating {@link SpatialElement} objects.
 *
 * <p>{@code SpatialSource} provides a declarative way to create spatial elements
 * on demand. Implementations can generate elements based on various criteria
 * such as time, frequency data, or algorithmic patterns.</p>
 *
 * <p>This interface follows the Factory pattern, allowing different spatial
 * generation strategies to be plugged into visualization or processing
 * pipelines.</p>
 *
 * @see SpatialElement
 * @see SpatialGroup
 */
public interface SpatialSource {

	/**
	 * Generates a new spatial element.
	 *
	 * <p>Each call may return a new element or the same element depending
	 * on the implementation. The generated element's position and properties
	 * are determined by the specific implementation.</p>
	 *
	 * @return a newly generated or existing {@link SpatialElement}
	 */
	SpatialElement generate();
}
