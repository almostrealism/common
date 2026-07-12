/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies that an all-computation {@link OperationList} whose later member reads memory
 * written by an earlier member produces correct results on every run, even when the
 * optimization strategies isolate a subtree of the reading member.
 *
 * <p>Without hazard-aware subdivision, such a list fuses into a single compiled operation;
 * an isolated subtree of the reading member is then evaluated at argument-preparation time,
 * before the fused kernel's earlier-member write lands — a deterministic one-pass lag (each
 * run computes from the previous run's input). The composite here is the minimal shape:
 * a record member whose source is a {@link DynamicCollectionProducer} (as a model's input
 * staging supplies it) followed by a dense computation reading the recorded buffer, with
 * shapes that cause the strategies to isolate the multiply.</p>
 */
public class OperationListSubdivisionTest extends TestSuiteBase implements ModelTestFeatures {

	/**
	 * Runs the optimized composite with a different input on every pass and requires
	 * each pass's output to reflect that pass's input.
	 */
	@Test(timeout = 120000)
	public void recordThenReadWithIsolation() {
		PackedCollection[] external = new PackedCollection[1];
		PackedCollection recorded = new PackedCollection(shape(2));

		PackedCollection weights = new PackedCollection(shape(1, 2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection bias = new PackedCollection(shape(1));
		bias.setMem(0, 0.1);

		PackedCollection layerOut = new PackedCollection(shape(1));
		PackedCollection modelOut = new PackedCollection(shape(1));

		DynamicCollectionProducer dynamicInput =
				new DynamicCollectionProducer(shape(2), args -> external[0]);

		CollectionProducer dense = matmul(p(weights),
				reshape(shape(1, 2).traverse(1), p(recorded)))
				.add(traverse(1, p(bias)))
				.reshape(shape(1));

		OperationList entry = new OperationList("entry");
		entry.add(into("record", (Producer) dynamicInput, p(recorded), false));
		entry.add(into("dense out", (Producer) dense, p(layerOut), false));

		OperationList forward = new OperationList("forward");
		forward.add(entry);
		forward.add(a("model out", p(modelOut), p(layerOut)));

		Runnable run = ((ParallelProcess<?, Runnable>) forward.flatten().optimize()).get();
		log("recordThenReadWithIsolation runnable=" + run.getClass().getSimpleName());

		double[][] inputs = { { 2.0, 3.0 }, { 4.0, 6.0 }, { 8.0, 12.0 } };
		double[] expected = { 0.35, 0.6, 1.1 };

		for (int i = 0; i < inputs.length; i++) {
			external[0] = new PackedCollection(shape(2));
			a(cp(external[0]), c(inputs[i][0], inputs[i][1])).get().run();

			run.run();
			double result = modelOut.toDouble(0);
			log("recordThenReadWithIsolation pass=" + (i + 1) +
					" result=" + result + " expected=" + expected[i]);
			assertEquals(expected[i], result);
		}
	}
}
