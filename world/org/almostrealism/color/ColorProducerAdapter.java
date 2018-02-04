package org.almostrealism.color;

import org.almostrealism.algebra.Triple;
import org.almostrealism.algebra.TripleFunction;
import org.almostrealism.util.Producer;

public abstract class ColorProducerAdapter implements ColorProducer {
	@Override
	public RGB operate(Triple in) { return evaluate(new Triple[] { in }); }

	@Override
	public void compact() { }

	public static ColorProducer fromFunction(TripleFunction<RGB> t) {
		return new ColorProducer() {
			@Override
			public RGB evaluate(Object args[]) {
				return operate((Triple) args[0]);
			}

			@Override
			public RGB operate(Triple in) {
				return t.operate(in);
			}

			@Override
			public void compact() {
				if (t instanceof Producer) ((Producer) t).compact();
			}
		};
	}
}
