package org.almostrealism.algebra.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.CodeFeatures;
import org.junit.Test;

public class AcceleratedComputationEvaluableTests implements HardwareFeatures, CodeFeatures {
	@Test
	public void staticProducer() {
		Producer<Vector> res = vector(0.0, 1.0, 2.0);
		Vector v = res.get().evaluate();
		System.out.println(v);
		assert v.getX() == 0.0;
		assert v.getY() == 1.0;
		assert v.getZ() == 2.0;
	}

	@Test
	public void scalarFromVector() {
		ExpressionComputation<Scalar> res = y(vector(0.0, 1.0, 2.0));
		Evaluable<Scalar> ev = res.get();
		Scalar s = ev.evaluate();
		System.out.println(s.getValue());
		assert s.getValue() == 1.0;
	}

	@Test
	public void scalarProduct() {
		ExpressionComputation<Scalar> x = scalar(3.0);
		Evaluable<Scalar> res = scalarsMultiply(x, scalar(0.5)).get();

		Scalar s = res.evaluate();
		System.out.println(s.getValue());
		System.out.println(s.getValue());
		assert s.getValue() == 1.5;
	}
}
