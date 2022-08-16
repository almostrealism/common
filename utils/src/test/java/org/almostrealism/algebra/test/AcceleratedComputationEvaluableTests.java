package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.CodeFeatures;
import org.junit.Test;

public class AcceleratedComputationEvaluableTests implements HardwareFeatures, CodeFeatures {
	@Test
	public void staticProducer() {
		VectorProducer res = vector(0.0, 1.0, 2.0);
		Vector v = res.get().evaluate();
		System.out.println(v);
		assert v.getX() == 0.0;
		assert v.getY() == 1.0;
		assert v.getZ() == 2.0;
	}

	@Test
	public void scalarFromVector() {
		ScalarExpressionComputation res = y(vector(0.0, 1.0, 2.0));
		AcceleratedComputationEvaluable ev = (AcceleratedComputationEvaluable) res.get();
		Scalar s = (Scalar) ev.evaluate();
		System.out.println(s.getValue());
		assert s.getValue() == 1.0;
	}

	@Test
	public void scalarProduct() {
		ScalarProducerBase x = scalar(3.0);
		AcceleratedComputationEvaluable<Scalar> res = (AcceleratedComputationEvaluable<Scalar>) scalarsMultiply(x, scalar(0.5)).get();

		Scalar s = res.evaluate();
		System.out.println(s.getValue());
		System.out.println(s.getValue());
		assert s.getValue() == 1.5;
	}
}
