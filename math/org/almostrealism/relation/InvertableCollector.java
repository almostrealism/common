package org.almostrealism.relation;

public interface InvertableCollector<P, C> extends Collector<P, C> {
	public Transformer extract(int index);
}
