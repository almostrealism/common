package com.almostrealism.hardware.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.VectorSum;
import org.almostrealism.hardware.AcceleratedComputationProducer;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.PassThroughProducer;
import org.almostrealism.util.Producer;
import org.almostrealism.util.Provider;
import org.junit.Test;

public class AcceleratedComputationOperationTest implements HardwareFeatures, CodeFeatures {
	@Test
	public void sum() {
		VectorProducer v = vector(1.0, 2.0, 3.0);
		Producer<Vector> in = PassThroughProducer.of(Vector.class, 0);

		AcceleratedComputationProducer<Vector> s = (AcceleratedComputationProducer) compileProducer(new VectorSum(v, in));
		System.out.println(s.getFunctionDefinition());
		System.out.println("----------------");
		// s.setEnableComputationEncoding(true);
		System.out.println(s.getFunctionDefinition());
	}
}
