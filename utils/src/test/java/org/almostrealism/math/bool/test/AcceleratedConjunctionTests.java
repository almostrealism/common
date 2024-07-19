/*
 * Copyright 2021 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.math.bool.test;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.geometry.Ray;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.bool.LessThan;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.junit.Assert;
import org.junit.Test;

import java.util.stream.IntStream;

public class AcceleratedConjunctionTests extends AcceleratedConditionalStatementTests {
	protected AcceleratedComputationEvaluable<Scalar> conjunction(
			Producer<Scalar> a, Producer<Scalar> b,
			Producer<Scalar> c, Producer<Scalar> d) {
		LessThan l1 = lessThan(a, b);
		LessThan l2 = lessThan(c, d);
		AcceleratedConjunctionScalar acs = new AcceleratedConjunctionScalar(a, b, l1, l2);
		return (AcceleratedComputationEvaluable<Scalar>) acs.get();
	}

	protected Runnable conjunctionTest(double a, double b, double c, double d) {
		Evaluable<Scalar> s = conjunction(scalar(a), v(Scalar.shape(), 0),
				scalar(c), v(Scalar.shape(), 1));

		return () -> {
			double t = s.evaluate(scalar(b).get().evaluate(), scalar(d).get().evaluate()).getValue();

			if (a < b && c < d) {
				assertEquals(a, t);
			} else {
				assertEquals(b, t);
			}
		};
	}

	@Test
	public void conjunctions() {
		if (testDepth < 1) return;

		IntStream.range(0, 10).mapToObj(i ->
			conjunctionTest(i * Math.random(), i * Math.random(), i * Math.random(), i * Math.random()))
				.forEach(Runnable::run);
	}

	protected AcceleratedComputationEvaluable<Scalar> dotProductConjunction(Producer<Ray> r) {
		return conjunction(oDotd(ray(i -> Math.random())), scalar(1), oDotd(v(Ray.shape(), 0)), scalar(1));
	}

	@Test
	public void dotProductInConjunction() {
		Evaluable<Scalar> c = dotProductConjunction(v(Ray.shape(), 0));
		if (enableArgumentCountAssertions) Assert.assertEquals(2, ((OperationAdapter) c).getArgsCount());

		double v = c.evaluate(ray(i -> Math.random()).get().evaluate()).getValue();
		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void dotProductInNestedConjunction1() {
		AcceleratedComputationEvaluable<Scalar> c = dotProductConjunction(ray(i -> Math.random()));
		c = conjunction(c.getComputation(), v(Scalar.shape(), 0),
				scalar(Math.random()), scalar(Math.random()));

		double v = c.evaluate(scalar(Math.random()).get().evaluate()).getValue();

		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void dotProductInNestedConjunction2() {
		AcceleratedComputationEvaluable<Scalar> c = dotProductConjunction(ray(i -> Math.random()));
		c = conjunction(c.getComputation(), scalar(Math.random()), v(Scalar.shape(), 0), scalar(Math.random()));

		double v = c.evaluate(scalar(Math.random()).get().evaluate()).getValue();

		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void dotProductInNestedConjunction3() {
		AcceleratedComputationEvaluable<Scalar> c = dotProductConjunction(v(Ray.shape(), 0));
		c = conjunction(c.getComputation(), scalar(Math.random()),
				scalar(Math.random()), scalar(Math.random()));

		double v = c.evaluate(ray(i -> Math.random()).get().evaluate()).getValue();

		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}

	@Test
	public void dotProductInNestedConjunction4() {
		AcceleratedComputationEvaluable<Scalar> c = dotProductConjunction(v(Ray.shape(), 0));
		c = conjunction(c.getComputation(), v(Scalar.shape(), 1),
				scalar(Math.random()), v(Scalar.shape(), 2));

		double v = c.evaluate(ray(i -> Math.random()).get().evaluate(),
							scalar(Math.random()).get().evaluate(),
							scalar(Math.random()).get().evaluate()).getValue();

		System.out.println(v);
		Assert.assertNotEquals(0, v);
	}
}
