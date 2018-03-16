package org.almostrealism.color;

import org.almostrealism.algebra.Triple;
import org.almostrealism.algebra.TripleFunction;
import org.almostrealism.algebra.Vector;
import org.almostrealism.util.Generated;
import org.almostrealism.util.Producer;

public abstract class GeneratedColorProducer<T> extends ColorProducerAdapter implements Generated<T> {
	private T generator;

	protected GeneratedColorProducer(T generator) {
		this.generator = generator;
	}

	public T getGenerator() { return generator; }

	public static <T> GeneratedColorProducer<T> fromFunction(T generator, TripleFunction<RGB> t) {
		return new GeneratedColorProducer(generator) {
			@Override
			public RGB evaluate(Object args[]) {
				return operate(args.length > 0 ? (Triple) args[0] : new Vector(1.0, 1.0, 1.0));
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
