/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.algebra.computations.test;

import io.almostrealism.code.OperationProfile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class MatrixMathTests implements TestFeatures {
	@Test
	public void matmul() {
		int dim = 768;
		int width = 2048;

		PackedCollection<?> matrix = new PackedCollection<>(dim, width);
		PackedCollection<?> vector = new PackedCollection<>(width);
		PackedCollection<?> result = new PackedCollection<>(dim);

		matrix.fill(pos -> Math.random());
		vector.fill(pos -> Math.random());

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("Matrix Test", false);
		op.add(a("matmul", traverseEach(p(result)), matmul(p(matrix), p(vector))));
		Runnable r = ((OperationList) op.optimize()).get(profiles);

		for (int i = 0; i < 1000; i++) {
//			HardwareOperator.verboseLog(() -> {
				r.run();
//			});
		}

		profiles.print();
	}
}
