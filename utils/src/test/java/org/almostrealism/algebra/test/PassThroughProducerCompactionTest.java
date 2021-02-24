package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.algebra.computations.ScalarSum;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.junit.Assert;
import org.junit.Test;

public class PassThroughProducerCompactionTest implements HardwareFeatures, CodeFeatures {
	protected DynamicProducerComputationAdapter<Scalar, Scalar> sum() {
		return new ScalarSum(
				PassThroughEvaluable.of(Scalar.class, 0),
				PassThroughEvaluable.of(Scalar.class, 1));
	}

	@Test
	public void applySum() {
		Scalar s = sum().get().evaluate(v(1.0).get().evaluate(),
										v(2.0).get().evaluate());
		Assert.assertEquals(3.0, s.getValue(), Math.pow(10, -10));
	}

	protected AcceleratedComputationEvaluable<Scalar> product() {
		return (AcceleratedComputationEvaluable)
				new ScalarProduct(sum(),
					PassThroughEvaluable.of(Scalar.class, 2)).get();
	}

	@Test
	public void applyProduct() {
		Scalar s = product().evaluate(v(1.0).get().evaluate(),
									v(2.0).get().evaluate(),
									v(3.0).get().evaluate());
		Assert.assertEquals(9.0, s.getValue(), Math.pow(10, -10));
	}

	@Test
	public void applyProductCompact() {
		AcceleratedComputationEvaluable<Scalar> p = product();
		// p.compact();
		System.out.println(p.getFunctionDefinition());

		Scalar s = p.evaluate(new Object[] { new Scalar(1.0),
											new Scalar(2.0),
											new Scalar(3.0) });
		Assert.assertEquals(9.0, s.getValue(), Math.pow(10, -10));
	}
}
