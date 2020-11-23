package io.almostrealism.lambda;

import org.almostrealism.relation.Evaluable;

public interface Realization<O extends Evaluable, P> {
	O realize(P params);
}
