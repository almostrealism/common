package org.almostrealism.math.bool.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.math.bool.LessThan;
import org.almostrealism.util.CodeFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class AcceleratedConditionalStatementTests implements CodeFeatures {
	@Test
	public void compact() {
		ScalarProducer a = scalar(Math.random());
		ScalarProducer b = scalar(Math.random());

		LessThan lt = new LessThan(2, () -> Scalar.blank(), a, b, a, b, false);

		lt.compact();
		System.out.println(lt.getFunctionDefinition());

		Scalar s = (Scalar) lt.evaluate();
		System.out.println(s.getValue());

		if (a.get().evaluate().getValue() < b.get().evaluate().getValue()) {
			Assert.assertEquals(a.get().evaluate().getValue(), s.getValue(), Math.pow(10, -10));
		} else {
			Assert.assertEquals(b.get().evaluate().getValue(), s.getValue(), Math.pow(10, -10));
		}
	}

	@Test
	public void compactNested() {
		IntStream.range(1, 10).forEach(i -> {
			double a = i * Math.random();
			double b = i * Math.random();
			double c = i * Math.random();
			double d = i * Math.random();

			ScalarProducer pa = scalar(a);
			ScalarProducer pb = scalar(b);
			ScalarProducer pc = scalar(c);
			ScalarProducer pd = scalar(d);

			LessThan lt1 = new LessThan(2, () -> Scalar.blank(), pa, pb, pa, pb, false);
			LessThan lt2 = new LessThan(2, () -> Scalar.blank(), pb, pc, () -> lt1, scalar(-a), false);
			LessThan lt3 = new LessThan(2, () -> Scalar.blank(), pc, pd, () -> lt2, scalar(-b), false);

			LessThan top = lt3;

			top.compact();
			System.out.println(top.getFunctionDefinition());

			Scalar s = (Scalar) top.evaluate();
			System.out.println(s.getValue());

			if (c < d) {
				if (b < c) {
					if (a < b) {
						Assert.assertEquals(a, s.getValue(), Math.pow(10, -10));
					} else {
						Assert.assertEquals(b, s.getValue(), Math.pow(10, -10));
					}
				} else {
					Assert.assertEquals(-a, s.getValue(), Math.pow(10, -10));
				}
			} else {
				Assert.assertEquals(-b, s.getValue(), Math.pow(10, -10));
			}
		});
	}
}
