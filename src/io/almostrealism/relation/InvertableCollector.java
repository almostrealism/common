package io.almostrealism.relation;

public interface InvertableCollector<P, C> extends Collector<P, C> {
	Transformer extract(int index);
}
