package io.almostrealism.lambda;

import org.almostrealism.util.Evaluable;

public interface Realization<O extends Evaluable, P> {
	O realize(P params);
}
