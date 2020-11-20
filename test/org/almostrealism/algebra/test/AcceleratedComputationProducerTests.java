package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarSupplier;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorSupplier;
import org.almostrealism.algebra.computations.ScalarFromVector;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.hardware.AcceleratedComputationProducer;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.CodeFeatures;
import org.junit.Test;

public class AcceleratedComputationProducerTests implements HardwareFeatures, CodeFeatures {
	@Test
	public void staticProducer() {
		VectorSupplier res = vector(0.0, 1.0, 2.0);
		Vector v = res.get().evaluate();
		System.out.println(v);
		assert v.getX() == 0.0;
		assert v.getY() == 1.0;
		assert v.getZ() == 2.0;
	}

	@Test
	public void scalarFromVector() {
		AcceleratedComputationProducer<Scalar> res = (AcceleratedComputationProducer) new ScalarFromVector(vector(0.0, 1.0, 2.0), ScalarFromVector.Y).get();
		System.out.println(res.getFunctionDefinition());
		Scalar s = res.evaluate(new Object[0]);
		System.out.println(s.getValue());
		assert s.getValue() == 1.0;
	}

	@Test
	public void compactScalarFromVector() {
		ScalarFromVector res = new ScalarFromVector(vector(0.0, 1.0, 2.0), ScalarFromVector.Y);
		res.compact();
		Scalar s = compileProducer(res).evaluate();
		System.out.println(s.getValue());
		assert s.getValue() == 1.0;
	}

	@Test
	public void scalarProduct() {
		ScalarSupplier x = scalar(3.0);
		AcceleratedComputationProducer<Scalar> res = (AcceleratedComputationProducer)
				compileProducer(new ScalarProduct(x, scalar(0.5)));
		System.out.println(res.getFunctionDefinition());

		Scalar s = res.evaluate();
		System.out.println(s.getValue());
		assert s.getValue() == 1.5;
	}
}
