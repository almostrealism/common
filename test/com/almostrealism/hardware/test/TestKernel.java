package com.almostrealism.hardware.test;

import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.util.Producer;

public class TestKernel extends AcceleratedProducer<Ray> {
	public TestKernel(Producer<Ray> blank, Producer<?>... inputArgs) {
		super("testKernel", true, blank, inputArgs);
	}
}
