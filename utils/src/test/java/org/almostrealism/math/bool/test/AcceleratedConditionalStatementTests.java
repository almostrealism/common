package org.almostrealism.math.bool.test;

import io.almostrealism.code.OperationAdapter;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.hardware.Input;
import org.almostrealism.util.TestSettings;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.bool.LessThanScalar;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.bool.AcceleratedConditionalStatementScalar;
import org.almostrealism.bool.LessThan;
import io.almostrealism.relation.Producer;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class AcceleratedConditionalStatementTests implements TestFeatures {
	@Test
	public void randomLessThan() {
		IntStream.range(1, 6).forEach(i -> {
			ScalarProducerBase a = scalar(i * Math.random());
			ScalarProducerBase b = scalar(i * Math.random());

			Evaluable<Scalar> lt = lessThan(a, b).get();

			Scalar s = lt.evaluate();
			System.out.println("lessThan = " + s.getValue());

			if (a.get().evaluate().getValue() < b.get().evaluate().getValue()) {
				assertEquals(a.get().evaluate().getValue(), s.getValue());
			} else {
				assertEquals(b.get().evaluate().getValue(), s.getValue());
			}
		});
	}

	@Test
	public void randomLessThanKernel() {
		PackedCollection<?> x = rand(shape(100, 2)).get().evaluate();
		PackedCollection<?> y = rand(shape(100, 2)).get().evaluate();

		PackedCollection<?> less = new PackedCollection<>(shape(100, 2), 1);
		lessThan().get().into(less).evaluate(x.traverse(1), y.traverse(1));

		Assert.assertEquals(100, less.getShape().length(0));
		Assert.assertEquals(2, less.getShape().length(1));

		IntStream.range(0, 100).forEach(i -> {
			double a = x.valueAt(i, 0);
			double b = y.valueAt(i, 0);
			double s = less.valueAt(i, 0);
			System.out.println("lessThan = " + s);

			if (a < b) {
				assertEquals(a, s);
			} else {
				assertEquals(b, s);
			}
		});
	}

	protected LessThan<Scalar> lessThan() {
		Producer<Scalar> one = Input.value(Scalar.shape(), 0);
		Producer<Scalar> two = Input.value(Scalar.shape(), 1);
		LessThan<Scalar> lt = lessThan(one, two);
		return lt;
	}

	protected LessThan<Scalar> lessThan(Producer<Scalar> a, Producer<Scalar> b) {
		return new LessThanScalar(a, b, a, b, false);
	}

	@Test
	public void withPassThrough() {
		IntStream.range(0, 5)
				.mapToObj(i -> scalar(Math.random()).get().evaluate())
				.forEach(a -> {
					Scalar b = scalar(Math.random()).get().evaluate();
					LessThan lt = lessThan();
					check(lt, a, b);
				});
	}

	@Test
	public void compactWithDotProduct() {
		Evaluable<Scalar> lt = lessThan(oDotd(ray(i -> Math.random())), oDotd(v(Ray.shape(), 0))).get();
		if (TestSettings.enableArgumentCountAssertions)
			Assert.assertEquals(2, ((OperationAdapter) lt).getArgsCount());

		double v = lt.evaluate(ray(i -> Math.random()).get().evaluate()).getValue();
		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void compactWithCrossProduct() {
		LessThan<Scalar> lt1 = lessThan(oDotd(ray(i -> Math.random())), oDotd(v(Ray.shape(), 0)));
		AcceleratedConditionalStatementScalar lt2 =
				lessThan(length(crossProduct(vector(i -> Math.random()), v(Vector.shape(), 1))),
														lt1, v(1), v(2), false);

		double v = lt2.get().evaluate(ray(i -> Math.random()).get().evaluate(), vector(i -> Math.random()).get().evaluate()).getValue();
		System.out.println(v);
		assert v == 1.0 || v == 2.0;
	}

	private void check(LessThan lt, Scalar a, Scalar b) {
		Evaluable ev = lt.get();
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

			ScalarProducerBase pa = scalar(a);
			ScalarProducerBase pb = scalar(b);
			ScalarProducerBase pc = scalar(c);
			ScalarProducerBase pd = scalar(d);

			LessThan lt1 = new LessThanScalar(pa, pb, pa, pb, false);
			LessThan lt2 = new LessThanScalar(pb, pc, lt1, scalar(-a), false);
			LessThan lt3 = new LessThanScalar(pc, pd, lt2, scalar(-b), false);

			LessThan top = lt3;

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
