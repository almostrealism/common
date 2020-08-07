package org.almostrealism.color.computations;

import org.almostrealism.algebra.Triple;
import org.almostrealism.color.RGB;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.algebra.Vector;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Generated;
import org.almostrealism.util.Producer;

public class GeneratedColorProducer<T> extends ColorProducerAdapter implements Generated<T> {
	private Producer<RGB> p;
	private T generator;

	protected GeneratedColorProducer(T generator) {
		this.generator = generator;
	}

	protected GeneratedColorProducer(T generator, Producer<RGB> p) {
		this.generator = generator;
		this.p = p;
	}

	public T getGenerator() { return generator; }

	public Producer<RGB> getProducer() {
		return p;
	}

	public static <T> GeneratedColorProducer<T> fromFunction(T generator, TripleFunction<RGB> t) {
		return new GeneratedColorProducer(generator, new DynamicProducer<>(args ->
				t.operate(args.length > 0 ? (Triple) args[0] : new Vector(1.0, 1.0, 1.0))));
	}

	public static <T> GeneratedColorProducer<T> fromProducer(T generator, Producer<RGB> p) {
		return new GeneratedColorProducer(generator, p);
	}

	@Override
	public RGB evaluate(Object args[]) {
		return p.evaluate(args);
	}

	@Override
	public RGB operate(Triple in) {
		return evaluate(new Object[] { in });
	}

	@Override
	public void compact() {
		p.compact();
	}
}
