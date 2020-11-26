package org.almostrealism.hardware.test;

import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public class TestKernel extends AcceleratedProducer<Ray, Ray> {
	public TestKernel(Supplier<Evaluable<? extends Ray>> blank, Supplier<Evaluable<? extends Ray>>... inputArgs) {
		super("testKernel", true, blank, inputArgs);
	}
}
