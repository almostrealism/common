package org.almostrealism.algebra;

import org.almostrealism.geometry.Ray;
import org.almostrealism.graph.NodeList;
import org.almostrealism.space.Gradient;
import org.almostrealism.util.Producer;

/**
 * A {@link DiscreteField} is a collection of points in space and corresponding directions.
 * It is similar to a {@link Gradient}, but is not continuously evaluable.
 * 
 * @author  Michael Murray
 */
public interface DiscreteField extends NodeList<Producer<Ray>> {

}
