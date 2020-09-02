package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.computations.ScalarFromVector;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;
import org.junit.Test;

public class DynamicAcceleratedProducerTests {
	@Test
	public void staticProducer() {
		Producer<Vector> res = StaticProducer.of(new Vector(0.0, 1.0, 2.0));
		Vector v = res.evaluate(new Object[0]);
		System.out.println(v);
		assert v.getX() == 0.0;
		assert v.getY() == 1.0;
		assert v.getZ() == 2.0;
	}

	@Test
	public void scalarFromVector() {
		ScalarFromVector res = new ScalarFromVector(new StaticProducer<>(new Vector(0.0, 1.0, 2.0)), ScalarFromVector.Y);
		System.out.println(res.getFunctionDefinition());
		Scalar s = res.evaluate(new Object[0]);
		System.out.println(s.getValue());
		assert s.getValue() == 1.0;
	}

	@Test
	public void compactScalarFromVector() {
		ScalarFromVector res = new ScalarFromVector(StaticProducer.of(new Vector(0.0, 1.0, 2.0)), ScalarFromVector.Y);
		res.compact();
		Scalar s = res.evaluate(new Object[0]);
		System.out.println(s.getValue());
		assert s.getValue() == 1.0;
	}

	@Test
	public void scalarProduct() {
		Producer<Scalar> x = StaticProducer.of(new Scalar(3.0));
		ScalarProduct res = new ScalarProduct(x, StaticProducer.of(new Scalar(0.5)));
		System.out.println(res.getFunctionDefinition());

		Scalar s = res.evaluate(new Object[0]);
		System.out.println(s.getValue());
		assert s.getValue() == 1.5;
	}
}