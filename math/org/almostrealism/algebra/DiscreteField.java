package org.almostrealism.algebra;

import java.util.concurrent.Callable;

import org.almostrealism.graph.NodeList;
import org.almostrealism.space.Gradient;

/**
 * A {@link DiscreteField} is a collection of points in space and corresponding directions.
 * It is similar to a {@link Gradient}, but is not continuously evaluable.
 * 
 * @author  Michael Murray
 */
public interface DiscreteField extends NodeList<Callable<Ray>> {

}
