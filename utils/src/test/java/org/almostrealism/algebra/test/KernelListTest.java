package org.almostrealism.algebra.test;

import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelList;
import org.almostrealism.algebra.ScalarTable;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.cl.HardwareOperator;
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

		ScalarBank output = new ScalarBank(4);
		multiply(scalar(() -> new Provider<>(paramsA), 0), new PassThroughProducer<>(shape(4, 2).traverse(1), 0)).get()
				.withDestination(output).evaluate(input);
		assertEquals(4.0, output.get(1));
	}

	@Test
	public void multiplyList() {
		HardwareOperator.verboseLog(() -> {
			ScalarBank input = new ScalarBank(4);
			input.set(0, 1);
			input.set(1, 2);
			input.set(2, 3);
			input.set(3, 4);

			ScalarBank paramsA = new ScalarBank(1);
			paramsA.set(0, 2);

			ScalarBank paramsB = new ScalarBank(1);
			paramsB.set(0, 3);

			KernelList kernels = new KernelList<>(ScalarBank::new, ScalarTable::new,
					(v, in) -> multiply(scalar(v, 0), in), 2, 1);
			kernels.setInput(input);
			kernels.setParameters(0, v(paramsA));
			kernels.setParameters(1, v(paramsB));
			kernels.get().run();
			assertEquals(4.0, (Scalar) kernels.valueAt(0).get(1));
			assertEquals(12.0, (Scalar) kernels.valueAt(1).get(3));
		});
	}
}
