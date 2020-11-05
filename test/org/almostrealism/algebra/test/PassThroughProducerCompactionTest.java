package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.algebra.computations.ScalarSum;
import org.almostrealism.hardware.AcceleratedComputationProducer;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.PassThroughProducer;
import org.junit.Assert;
import org.junit.Test;

public class PassThroughProducerCompactionTest implements HardwareFeatures {
	protected AcceleratedComputationProducer<Scalar> product() {
		return (AcceleratedComputationProducer)
				compileProducer(new ScalarProduct(compileProducer(new ScalarSum(
					PassThroughProducer.of(Scalar.class, 0),
					PassThroughProducer.of(Scalar.class, 1))),
				PassThroughProducer.of(Scalar.class, 2)));
	}

	@Test
	public void apply() {
		Scalar s = product().evaluate(new Object[] { new Scalar(1.0),
													new Scalar(2.0),
													new Scalar(3.0) });
		Assert.assertEquals(9.0, s.getValue(), Math.pow(10, -10));
	}

	@Test
	public void applyCompact() {
		AcceleratedComputationProducer<Scalar> p = product();
		p.compact();
		System.out.println(p.getFunctionDefinition());

		Scalar s = p.evaluate(new Object[] { new Scalar(1.0),
											new Scalar(2.0),
											new Scalar(3.0) });
		Assert.assertEquals(9.0, s.getValue(), Math.pow(10, -10));
	}
}
