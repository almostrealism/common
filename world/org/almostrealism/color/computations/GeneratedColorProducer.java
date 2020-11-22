package org.almostrealism.color.computations;

import org.almostrealism.algebra.Triple;
import org.almostrealism.color.RGB;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.relation.Computation;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.algebra.Vector;
import org.almostrealism.util.DynamicEvaluable;
import org.almostrealism.util.Generated;
import org.almostrealism.util.Evaluable;

public class GeneratedColorProducer<T> extends ColorEvaluableAdapter implements Generated<T> {
	private Evaluable<RGB> p;
	private T generator;

	protected GeneratedColorProducer(T generator) {
		this.generator = generator;
	}

	protected GeneratedColorProducer(T generator, Evaluable<RGB> p) {
		this.generator = generator;
		this.p = p;
	}

	public T getGenerator() { return generator; }

	public Evaluable<RGB> getProducer() {
		return p;
	}

	public static <T> GeneratedColorProducer<T> fromFunction(T generator, TripleFunction<Triple, RGB> t) {
		return new GeneratedColorProducer(generator, new DynamicEvaluable<>(args ->
				t.operate(args.length > 0 ? (Triple) args[0] : new Vector(1.0, 1.0, 1.0))));
	}

	public static <T> GeneratedColorProducer<T> fromProducer(T generator, Evaluable<? extends RGB> p) {
		return new GeneratedColorProducer(generator, p);
	}

	public static <T> GeneratedColorProducer<T> fromComputation(T generator, Computation<RGB> c) {
		return fromProducer(generator,
				Hardware.getLocalHardware().getComputer().compileProducer(c));
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
