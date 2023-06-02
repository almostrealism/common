/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.algebra.test;

import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.KernelList;
import org.almostrealism.algebra.ScalarTable;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class KernelListTest implements TestFeatures {
	@Test
	public void multiply() {
		PackedCollection<Scalar> input = Scalar.scalarBank(4);
		input.set(0, 1);
		input.set(1, 2);
		input.set(2, 3);
		input.set(3, 4);

		PackedCollection<Scalar> paramsA = Scalar.scalarBank(1);
		paramsA.set(0, 2);

		PackedCollection<Scalar> output = Scalar.scalarBank(4);
		multiply(scalar(() -> new Provider<>(paramsA), 0), new PassThroughProducer<>(shape(4, 2).traverse(1), 0)).get()
				.withDestination(output).evaluate(input);
		assertEquals(4.0, output.get(1));
	}

	@Test
	public void multiplyList() {
		HardwareOperator.verboseLog(() -> {
			PackedCollection<Scalar> input = Scalar.scalarBank(4);
			input.set(0, 1);
			input.set(1, 2);
			input.set(2, 3);
			input.set(3, 4);

			PackedCollection<Scalar> paramsA = Scalar.scalarBank(1);
			paramsA.set(0, 2);

			PackedCollection<Scalar> paramsB = Scalar.scalarBank(1);
			paramsB.set(0, 3);

			KernelList kernels = new KernelList<>(Scalar::scalarBank, ScalarTable::new,
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
