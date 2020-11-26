package org.almostrealism.hardware.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.VectorSum;
import org.almostrealism.hardware.AcceleratedComputationProducer;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.PassThroughEvaluable;
import org.almostrealism.relation.Evaluable;
import org.junit.Test;

import java.util.function.Supplier;

public class AcceleratedComputationOperationTest implements HardwareFeatures, CodeFeatures {
	@Test
	public void sum() {
		VectorProducer v = vector(1.0, 2.0, 3.0);
		Supplier<Evaluable<? extends Vector>> in = PassThroughEvaluable.of(Vector.class, 0);

		AcceleratedComputationProducer<Vector> s = (AcceleratedComputationProducer) compileProducer(new VectorSum(v, in));
		System.out.println(s.getFunctionDefinition());
		System.out.println("----------------");
		// s.setEnableComputationEncoding(true);
		System.out.println(s.getFunctionDefinition());
	}
}
