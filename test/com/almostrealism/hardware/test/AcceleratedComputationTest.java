package com.almostrealism.hardware.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.VectorSum;
import org.almostrealism.util.PassThroughProducer;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;
import org.junit.Test;

public class AcceleratedComputationTest {
	@Test
	public void sum() {
		VectorProducer v = StaticProducer.of(new Vector(1.0, 2.0, 3.0));
		Producer<Vector> in = PassThroughProducer.of(Vector.class, 0);

		VectorSum s = new VectorSum(v, in);
		System.out.println(s.getFunctionDefinition());
		System.out.println("----------------");
		s.setEnableComputationEncoding(true);
		System.out.println(s.getFunctionDefinition());
	}
}
