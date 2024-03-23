/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.OperationProfile;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class AltComputeContextsTest implements TestFeatures {
	// TODO  @Test
	public void clAndNative() {
		dc(() -> {
			Scalar result = new Scalar();

			Producer<Scalar> sum = scalarAdd(scalar(1.0), scalar(2.0));
			Producer<Scalar> product = scalarsMultiply(scalar(3.0), scalar(2.0));

			cc(() -> a(2, p(result), sum).get().run(), ComputeRequirement.CL);
			System.out.println("Result = " + result.getValue());

			cc(() -> a(2, p(result), product).get().run(), ComputeRequirement.C);
			System.out.println("Result = " + result.getValue());
		});
	}

	@Test
	public void matmul() {
		boolean enableOptimization = true;
		boolean validate = true;
		int dim = 128;
		int width = 64;
//		int width = 2048;

		PackedCollection<?> matrix = new PackedCollection<>(dim, width);
		PackedCollection<?> vector = new PackedCollection<>(width);
		PackedCollection<?> result = new PackedCollection<>(dim);

		matrix.fill(pos -> Math.random());
		vector.fill(pos -> Math.random());

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("Matrix Test", false);
		op.add(a("matmul " + width, traverseEach(p(result)), matmul(p(matrix), p(vector))));
		Runnable r = enableOptimization ? ((OperationList) op.optimize()).get(profiles) : op.get(profiles);

		HardwareOperator.verboseLog(() -> r.run());

		if (!skipLongTests) {
			profiles.clear();

			for (int i = 0; i < 5000; i++) {
				r.run();
			}
		}

		profiles.print();

		if (validate) {
			for (int i = 0; i < dim; i++) {
				double v = 0.0;

				for (int j = 0; j < width; j++) {
					v += matrix.valueAt(i, j) * vector.valueAt(j);
				}

				assertEquals(v, result.valueAt(i));
			}
		}
	}
}
