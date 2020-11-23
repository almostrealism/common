package io.almostrealism.lambda;

import org.almostrealism.relation.Producer;

public interface Realization<O extends Producer, P> {
	O realize(P params);
}
