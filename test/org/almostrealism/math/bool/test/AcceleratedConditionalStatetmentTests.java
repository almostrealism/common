package org.almostrealism.math.bool.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.math.bool.LessThan;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;
import org.junit.Test;

public class AcceleratedConditionalStatetmentTests {
	@Test
	public void compactNested() {
		Producer<Scalar> a = StaticProducer.of(new Scalar(Math.random()));
		Producer<Scalar> b = StaticProducer.of(new Scalar(Math.random()));
		Producer<Scalar> c = StaticProducer.of(new Scalar(Math.random()));
		Producer<Scalar> d = StaticProducer.of(new Scalar(Math.random()));

		LessThan lt1 = new LessThan(2, Scalar.blank(), a, b, a, b);
		LessThan lt2 = new LessThan(2, Scalar.blank(), b, c, lt1, StaticProducer.of(new Scalar(0.0)));
		// LessThan lt3 = new LessThan(2, Scalar.blank(), c, d, lt2, StaticProducer.of(new Scalar(0.0)));

		LessThan top = lt1;

		top.compact();
		System.out.println(top.getFunctionDefinition());

		Scalar s = (Scalar) top.evaluate(new Object[0]);
		System.out.println(s.getValue());
	}
}
