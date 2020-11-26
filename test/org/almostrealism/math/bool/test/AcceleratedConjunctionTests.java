package org.almostrealism.math.bool.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.math.bool.AcceleratedConjunctionScalar;
import org.almostrealism.math.bool.LessThan;
import org.almostrealism.relation.Producer;
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
}
