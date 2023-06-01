package org.almostrealism.algebra.computations.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class ScalarBankDotProductTest implements TestFeatures {
	public static final int SIZE = 32;

	public ScalarBank window() {
		ScalarBank window = new ScalarBank(SIZE);
		IntStream.range(0, SIZE).forEach(i -> window.set(i, i * 4, i * 10));
		return window;
	}

	@Test
	public void scalarBankDotProduct32() {
		ScalarBank window = window();

		Scalar given = new Scalar(IntStream.range(0, SIZE)
				.mapToDouble(i -> window.get(i).getValue() * window.get(i).getValue()).sum());

		Producer<PackedCollection<?>> a = subset(shape(SIZE, 1), v(shape(SIZE, 2), 0), 0);
		Producer<PackedCollection<?>> b = subset(shape(SIZE, 1), v(shape(SIZE, 2), 1), 0);
		Evaluable<? extends Scalar> ev = scalar(multiply(a, b).sum()).get();

		Scalar test = ev.evaluate(window(), window());
		System.out.println(test);
		assertEquals(given, test);
	}
}
