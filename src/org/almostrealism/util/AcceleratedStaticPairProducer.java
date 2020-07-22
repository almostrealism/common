package org.almostrealism.util;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairProducer;

public class AcceleratedStaticPairProducer extends AcceleratedStaticProducer<Pair> implements PairProducer {
	public AcceleratedStaticPairProducer(Pair value, Producer<Pair> output) {
		super(value, output);
	}
}
