package org.almostrealism.math.bool.test;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.test.TestSettings;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.bool.LessThanScalar;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.DynamicAcceleratedOperation;
import org.almostrealism.bool.AcceleratedConditionalStatementScalar;
import org.almostrealism.bool.LessThan;
import io.almostrealism.relation.Producer;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class AcceleratedConditionalStatementTests implements TestFeatures {
	@Test
	public void compact() {
		ScalarProducer a = scalar(Math.random());
		ScalarProducer b = scalar(Math.random());

		LessThan lt = new LessThanScalar(a, b, a, b, false);
		lt.compile();

		Scalar s = (Scalar) lt.get().evaluate();
		System.out.println(s.getValue());

		if (a.get().evaluate().getValue() < b.get().evaluate().getValue()) {
			Assert.assertEquals(a.get().evaluate().getValue(), s.getValue(), Math.pow(10, -10));
		} else {
			Assert.assertEquals(b.get().evaluate().getValue(), s.getValue(), Math.pow(10, -10));
		}
	}

	protected LessThan<Scalar> lessThan() {
		Producer<Scalar> one = PassThroughEvaluable.of(Scalar.class, 0);
		Producer<Scalar> two = PassThroughEvaluable.of(Scalar.class, 1);
		LessThan<Scalar> lt = lessThan(one, two);
		return lt;
	}

	protected LessThan<Scalar> lessThan(Producer<Scalar> a, Producer<Scalar> b) {
		return new LessThanScalar(a, b, a, b, false);
	}

	@Test
	public void withPassThrough() {
		IntStream.range(0, 5).forEach(i -> {
			Scalar a = scalar(Math.random()).get().evaluate();
			Scalar b = scalar(Math.random()).get().evaluate();

			LessThan lt = lessThan();
			check(lt, a, b);
		});
	}

	@Test
	public void compactWithDotProduct() {
		Evaluable<Scalar> lt = lessThan(ray(i -> Math.random()).oDotd(), oDotd(v(Ray.class, 0))).get();
		((OperationAdapter) lt).compile();
		if (TestSettings.enableArgumentCountAssertions)
			Assert.assertEquals(2, ((OperationAdapter) lt).getArgsCount());

		double v = lt.evaluate(ray(i -> Math.random()).get().evaluate()).getValue();
		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void compactWithCrossProduct() {
		LessThan<Scalar> lt1 = lessThan(ray(i -> Math.random()).oDotd(), oDotd(v(Ray.class, 0)));
		AcceleratedConditionalStatementScalar lt2 = vector(i -> Math.random()).crossProduct(v(Vector.class, 1))
														.length().lessThan(lt1, v(1), v(2));
		((OperationAdapter) lt2).compile();

		double v = lt2.get().evaluate(ray(i -> Math.random()).get().evaluate(), vector(i -> Math.random()).get().evaluate()).getValue();
		System.out.println(v);
		assert v == 1.0 || v == 2.0;
	}

	private void check(LessThan lt, Scalar a, Scalar b) {
		Evaluable ev = lt.get();
		((OperationAdapter) ev).compile();
		System.out.println(((DynamicAcceleratedOperation) ev).getFunctionDefinition());

		Scalar s = (Scalar) ev.evaluate(a, b);
		System.out.println(s.getValue());

		if (a.getValue() < b.getValue()) {
			Assert.assertEquals(a.getValue(), s.getValue(), Math.pow(10, -10));
		} else {
			Assert.assertEquals(b.getValue(), s.getValue(), Math.pow(10, -10));
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

			LessThan lt1 = new LessThanScalar(pa, pb, pa, pb, false);
			LessThan lt2 = new LessThanScalar(pb, pc, lt1, scalar(-a), false);
			LessThan lt3 = new LessThanScalar(pc, pd, lt2, scalar(-b), false);

			LessThan top = lt3;

			top.compile();

			Scalar s = (Scalar) top.get().evaluate();
			System.out.println(s.getValue());

			if (c < d) {
				if (b < c) {
					if (a < b) {
						assertEquals(a, s.getValue());
					} else {
						assertEquals(b, s.getValue());
					}
				} else {
					assertEquals(-a, s.getValue());
				}
			} else {
				assertEquals(-b, s.getValue());
			}
		});
	}
}
