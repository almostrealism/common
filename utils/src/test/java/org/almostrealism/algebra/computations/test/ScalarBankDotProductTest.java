package org.almostrealism.algebra.computations.test;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.ScalarBankDotProduct;
import org.almostrealism.algebra.computations.jni.NativeScalarBankDotProduct;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.Hardware;
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
	public void nativeScalarBankDotProduct32() {
		ScalarBank window = window();

		Scalar given = new Scalar(IntStream.range(0, SIZE)
				.mapToDouble(i -> window.get(i).getValue() * window.get(i).getValue()).sum());

		Evaluable<? extends Scalar> ev = new ScalarBankDotProduct(SIZE,
				v(2 * SIZE, 0), v(2 * SIZE, 0)).get();

		Scalar test = ev.evaluate(window(), window());
		System.out.println(test);
		assertEquals(given, test);

		assert Hardware.getLocalHardware().isNativeSupported();

		ev = NativeScalarBankDotProduct.get(SIZE);

		((OperationAdapter) ev).compile();

		test = ev.evaluate(window(), window());
		System.out.println(test);

		assertEquals(given, test);
	}
}
