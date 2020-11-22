package com.almostrealism.hardware.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorSupplier;
import org.almostrealism.algebra.computations.VectorSum;
import org.almostrealism.hardware.AcceleratedComputationProducer;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.PassThroughProducer;
import org.almostrealism.util.Evaluable;
import org.junit.Test;

import java.util.function.Supplier;

public class AcceleratedComputationOperationTest implements HardwareFeatures, CodeFeatures {
	@Test
	public void sum() {
		VectorSupplier v = vector(1.0, 2.0, 3.0);
		Supplier<Evaluable<? extends Vector>> in = PassThroughProducer.of(Vector.class, 0);

		AcceleratedComputationProducer<Vector> s = (AcceleratedComputationProducer) compileProducer(new VectorSum(v, in));
		System.out.println(s.getFunctionDefinition());
		System.out.println("----------------");
		// s.setEnableComputationEncoding(true);
		System.out.println(s.getFunctionDefinition());
	}
}
