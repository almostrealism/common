package com.almostrealism.hardware.test;

import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.util.Producer;

import java.util.function.Supplier;

public class TestKernel extends AcceleratedProducer<Ray, Ray> {
	public TestKernel(Supplier<Producer<Ray>> blank, Supplier<Producer<? extends Ray>>... inputArgs) {
		super("testKernel", true, blank, inputArgs);
	}
}
