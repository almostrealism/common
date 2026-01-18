package org.almostrealism.geometry;

import org.almostrealism.algebra.Gradient;
import org.almostrealism.collect.PackedCollection;

/**
 * A {@link ContinuousField} extends {@link DiscreteField} to represent a set of points
 * in space with directions that can also be continuously evaluated as a gradient.
 *
 * <p>This interface combines two concepts:</p>
 * <ul>
 *   <li>{@link DiscreteField}: A collection of specific points with directions</li>
 *   <li>{@link Gradient}: The ability to compute a normal/gradient at any point</li>
 * </ul>
 *
 * <p>The {@link Gradient}'s normal vector does not need to match the direction at each
 * discrete point, allowing representation of concepts where a point has both:</p>
 * <ul>
 *   <li>A primary direction (stored in the discrete field)</li>
 *   <li>A derivative/gradient direction (computed via the Gradient interface)</li>
 * </ul>
 *
 * <p>Example uses:</p>
 * <ul>
 *   <li>Surface intersections where points have both normals and motion derivatives</li>
 *   <li>Particle systems with velocity (discrete) and acceleration (gradient)</li>
 *   <li>Flow fields with both flow direction and rate of change</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see DiscreteField
 * @see Gradient
 */
public interface ContinuousField extends DiscreteField, Gradient<PackedCollection> {

}
