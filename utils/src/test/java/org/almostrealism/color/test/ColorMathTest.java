package org.almostrealism.color.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.GreaterThanRGB;
import org.almostrealism.color.computations.RGBBlack;
import org.almostrealism.color.computations.RGBWhite;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ColorMathTest implements TestFeatures {
	@Test
	public void fixedSum() {
		HardwareOperator.verboseLog(() -> {
			Producer<RGB> p1 = new RGBBlack();
			Producer<RGB> p2 = new RGBWhite();
			Producer<RGB> sum = add(p1, p2);

			PackedCollection<?> result = sum.get().evaluate();
			assertEquals(1.0, result.toDouble(0));
			assertEquals(1.0, result.toDouble(1));
			assertEquals(1.0, result.toDouble(2));
		});
	}

	@Test
	public void greaterThan() {
		HardwareOperator.verboseLog(() -> {
			Producer<Scalar> arg0 = v(Scalar.shape(), 0);
			Producer<RGB> arg1 = v(RGB.shape(), 1);

			GreaterThanRGB greater = new GreaterThanRGB(arg0, v(0.0), arg1, RGBBlack.getInstance());
			RGB result = greater.get().evaluate(new Scalar(0.1), new RGB(0.0, 1.0, 0.0));
			assertEquals(0.0, result.getRed());
			assertEquals(1.0, result.getGreen());
			assertEquals(0.0, result.getBlue());
		});
	}

	@Test
	public void greaterThanKernel() {
		HardwareOperator.verboseLog(() -> {
			Producer<Scalar> arg0 = v(Scalar.shape(), 0);

			PackedCollection<RGB> result = RGB.bank(5);
			ScalarBank input = new ScalarBank(5);
			input.set(0, 0.0);
			input.set(1, -1.0);
			input.set(2, 1.0);
			input.set(3, -0.1);
			input.set(4, 0.1);

			GreaterThanRGB greater = new GreaterThanRGB(arg0, v(0.0), RGBWhite.getInstance(), RGBBlack.getInstance());
			greater.get().kernelEvaluate(result, input);
			assertEquals(0.0, result.get(0).getGreen());
			assertEquals(0.0, result.get(1).getGreen());
			assertEquals(1.0, result.get(2).getGreen());
			assertEquals(0.0, result.get(3).getGreen());
			assertEquals(1.0, result.get(4).getGreen());
		});
	}
}
