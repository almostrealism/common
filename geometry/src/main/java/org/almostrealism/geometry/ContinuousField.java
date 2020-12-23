package org.almostrealism.geometry;

import org.almostrealism.algebra.Gradient;
import org.almostrealism.algebra.Vector;

/**
 * A {@link ContinuousField} extends {@link DiscreteField} to represent a set of points
 * in space and corresponding directions, but it also can be continuously evaluated as
 * a gradient. There is no need for the {@link Gradient}'s normal vector to match the
 * direction for discrete each point in space meaning that a concept in three dimensional
 * space which has a separate primary direction and normal direction can be represented.
 * One example of this would be a concept where each point in space has a direction of
 * motion, but also a derivative, ie a direction of change in motion. 
 * 
 * @author  Michael Murray
 */
public interface ContinuousField extends DiscreteField, Gradient<Vector> {

}
