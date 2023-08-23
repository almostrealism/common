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

package org.almostrealism.collect.computations.test;

import io.almostrealism.code.OperationProfile;
import io.almostrealism.code.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.hardware.metal.MetalOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class OperationSemaphoreTests implements TestFeatures {
	@Test
	public void sum() {
		sum(50, 2048, 1024, false);
	}

	@Test
	public void sumPowers() {
		for (int i = 1; i < 9; i++) {
			sum(10, 100, 1 << i, false);
		}
	}

	protected void sum(int ops, int count, int dim, boolean validate) {
		OperationProfile profiles = new OperationProfile();
		OperationList op = new OperationList("Vector Test", false);

		List<PackedCollection<?>> allVectors = new ArrayList<>();
		List<PackedCollection<?>> allResults = new ArrayList<>();
		
		for (int i = 0; i < ops; i++) {
			PackedCollection<?> vectors = new PackedCollection<>(count, dim);
			PackedCollection<?> result = new PackedCollection<>(count);
			vectors.fill(pos -> Math.random());

			op.add(a("sum " + dim, traverseEach(p(result)), sum(traverse(1, p(vectors)))));
			allVectors.add(vectors);
			allResults.add(result);
		}

		Runnable r = op.get(profiles);

		long waitTime = 0;

		MetalOperator.verboseLog(() -> {
			r.run();
		});
		profiles.clear();

		for (int i = 0; i < 1000; i++) {
			r.run();
			long start = System.currentTimeMillis();
			AcceleratedOperation.waitFor();
			waitTime += (System.currentTimeMillis() - start);
		}

		System.out.println("Semaphore wait time: " + waitTime + "ms");
		profiles.print();

		if (validate) {
			for (int n = 0; n < ops; n++) {
				for (int i = 0; i < count; i++) {
					double expected = 0.0;

					for (int j = 0; j < dim; j++) {
						expected += allVectors.get(n).valueAt(i, j);
					}

					assertEquals(expected, allResults.get(n).valueAt(i));
				}
			}
		}
	}
}
