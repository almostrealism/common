package org.almostrealism.color;

import org.almostrealism.algebra.Triple;

public abstract class ColorProducerAdapter implements ColorProducer {
	@Override
	public RGB operate(Triple in) { return evaluate(new Triple[] { in }); }
}
