package org.almostrealism.algebra;

import org.almostrealism.algebra.computations.ScalarFromPair;
import org.almostrealism.util.Producer;

public interface PairProducer extends Producer<Pair> {
	default ScalarProducer x() { return new ScalarFromPair(this, ScalarFromPair.X); }
	default ScalarProducer y() { return new ScalarFromPair(this, ScalarFromPair.Y); }
}
