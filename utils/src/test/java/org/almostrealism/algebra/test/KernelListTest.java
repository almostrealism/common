package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.hardware.KernelList;
import org.almostrealism.algebra.ScalarTable;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class KernelListTest implements TestFeatures {
	@Test
	public void multiply() {
		ScalarBank input = new ScalarBank(4);
		input.set(0, 1);
		input.set(1, 2);
		input.set(2, 3);
		input.set(3, 4);

		ScalarBank paramsA = new ScalarBank(1);
		paramsA.set(0, 2);

		ScalarBank paramsB = new ScalarBank(1);
		paramsB.set(0, 3);

		KernelList kernels = new KernelList<>(Scalar.class, ScalarBank::new, ScalarTable::new,
				(v, in) -> scalar(v, 0).multiply(in), 2, 1);
		kernels.setInput(input);
		kernels.setParameters(0, v(paramsA));
		kernels.setParameters(1, v(paramsB));
		kernels.get().run();
		assertEquals(4.0, (Scalar) kernels.valueAt(0).get(1));
		assertEquals(12.0, (Scalar) kernels.valueAt(1).get(3));
	}
}
