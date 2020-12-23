package org.almostrealism.math.bool.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.geometry.Ray;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.bool.LessThan;
import io.almostrealism.relation.Producer;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class AcceleratedConjunctionTests extends AcceleratedConditionalStatementTests {
	protected AcceleratedConjunctionScalar conjunction(
			Producer<Scalar> a, Producer<Scalar> b,
			Producer<Scalar> c, Producer<Scalar> d) {
		LessThan l1 = lessThan(a, b);
		LessThan l2 = lessThan(c, d);
		return new AcceleratedConjunctionScalar(a, b, l1, l2);
	}

	protected Runnable conjunctionTest(double a, double b, double c, double d) {
		AcceleratedConjunctionScalar s = conjunction(v(a), v(Scalar.class, 0),
				v(c), v(Scalar.class, 1));

		return () -> {
			double t = s.evaluate(v(b).get().evaluate(), v(d).get().evaluate()).getValue();

			if (a < b && c < d) {
				Assert.assertEquals(a, t, Math.pow(10, -10));
			} else {
				Assert.assertEquals(b, t, Math.pow(10, -10));
			}
		};
	}

	@Test
	public void conjunctions() {
		IntStream.range(0, 10).mapToObj(i ->
			conjunctionTest(i * Math.random(), i * Math.random(), i * Math.random(), i * Math.random()))
				.forEach(Runnable::run);
	}

	protected AcceleratedConjunctionScalar dotProductConjunction(Producer<Ray> r) {
		return conjunction(ray(i -> Math.random()).oDotd(), v(1), oDotd(v(Ray.class, 0)), v(1));
	}

	@Test
	public void dotProductInConjunction() {
		AcceleratedConjunctionScalar c = dotProductConjunction(v(Ray.class, 0));
		c.compact();

		System.out.println(c.getFunctionDefinition());
		Assert.assertEquals(2, c.getArgsCount());

		double v = c.evaluate(ray(i -> Math.random()).get().evaluate()).getValue();
		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void dotProductInNestedConjunction1() {
		AcceleratedConjunctionScalar c = dotProductConjunction(ray(i -> Math.random()));
		c = conjunction(c, v(Scalar.class, 0),
				v(Math.random()), v(Math.random()));
		c.compact();

		System.out.println(c.getFunctionDefinition());

		double v = c.evaluate(v(Math.random()).get().evaluate()).getValue();

		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void dotProductInNestedConjunction2() {
		AcceleratedConjunctionScalar c = dotProductConjunction(ray(i -> Math.random()));
		c = conjunction(c, v(Math.random()), v(Scalar.class, 0), v(Math.random()));
		c.compact();

		System.out.println(c.getFunctionDefinition());

		double v = c.evaluate(v(Math.random()).get().evaluate()).getValue();

		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void dotProductInNestedConjunction3() {
		AcceleratedConjunctionScalar c = dotProductConjunction(v(Ray.class, 0));
		c = conjunction(c, v(Math.random()),
				v(Math.random()), v(Math.random()));
		c.compact();

		System.out.println(c.getFunctionDefinition());

		double v = c.evaluate(ray(i -> Math.random()).get().evaluate()).getValue();

		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void dotProductInNestedConjunction4() {
		AcceleratedConjunctionScalar c = dotProductConjunction(v(Ray.class, 0));
		c = conjunction(c, v(Scalar.class, 1),
				v(Math.random()), v(Scalar.class, 2));
		c.compact();

		System.out.println(c.getFunctionDefinition());

		double v = c.evaluate(ray(i -> Math.random()).get().evaluate(),
							v(Math.random()).get().evaluate(),
							v(Math.random()).get().evaluate()).getValue();

		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}
}
