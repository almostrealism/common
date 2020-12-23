package org.almostrealism.geometry;

import io.almostrealism.code.NodeList;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Gradient;

/**
 * A {@link DiscreteField} is a collection of points in space and corresponding directions.
 * It is similar to a {@link Gradient}, but is not continuously evaluable.
 * 
 * @author  Michael Murray
 */
public interface DiscreteField extends NodeList<Producer<Ray>> {

}
