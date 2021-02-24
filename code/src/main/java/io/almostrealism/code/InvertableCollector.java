package io.almostrealism.code;

import io.almostrealism.relation.Transformer;

public interface InvertableCollector<P, C> extends Collector<P, C> {
	Transformer extract(int index);
}
