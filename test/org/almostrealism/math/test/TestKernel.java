package org.almostrealism.math.test;

import org.almostrealism.geometry.Ray;
import org.almostrealism.math.AcceleratedProducer;
import org.almostrealism.util.Producer;

public class TestKernel extends AcceleratedProducer<Ray> {
	public TestKernel(Producer<?>... inputArgs) {
		super("testKernel", true, inputArgs);
	}
}
