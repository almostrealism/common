package org.almostrealism.hardware.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.VectorSum;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.CodeFeatures;
import org.almostrealism.hardware.PassThroughEvaluable;
import io.almostrealism.relation.Evaluable;
import org.junit.Test;

import java.util.function.Supplier;

public class AcceleratedComputationOperationTest implements HardwareFeatures, CodeFeatures {
	@Test
	public void sum() {
		VectorProducer v = vector(1.0, 2.0, 3.0);
		Supplier<Evaluable<? extends Vector>> in = PassThroughEvaluable.of(Vector.class, 0);

		AcceleratedComputationEvaluable<Vector> s = (AcceleratedComputationEvaluable) compileProducer(new VectorSum(v, in));
	}
}
