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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.OperationListRunner;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

/**
 * Verifies that argument preparation for an {@link org.almostrealism.hardware.AcceleratedOperation}
 * respects the {@link io.almostrealism.concurrent.Semaphore} chain: an argument evaluation that is
 * itself a dispatch reading memory written by a prior member of the same composite must be ordered
 * after that member's completion.
 *
 * <p>The composite executes through the {@link OperationListRunner} with chained submission (a
 * trailing plain {@link Runnable} prevents fusion into a single kernel without introducing any
 * wait between the members). Member one is a Metal-pinned kernel writing a buffer; member two is
 * a CPU-pinned kernel whose source contains an explicitly isolated subtree reading that buffer,
 * so the subtree is evaluated as a nested dispatch during member two's argument preparation. The
 * cross-context read must not observe the buffer from before member one's command buffer
 * committed.</p>
 */
public class ArgumentPreparationChainTest extends TestSuiteBase implements TestFeatures {

	/**
	 * Runs the two-member cross-context composite several times with fresh values
	 * each pass and requires member two's isolated argument to observe member one's
	 * write on every pass.
	 */
	@Test(timeout = 120000)
	public void crossContextArgumentObservesPriorMemberWrite() {
		if (Hardware.getLocalHardware()
				.getComputeContexts(false, true, ComputeRequirement.MTL).stream()
				.noneMatch(MetalComputeContext.class::isInstance)) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int n = 8;

		PackedCollection source = new PackedCollection(shape(n));
		PackedCollection written = new PackedCollection(shape(n));
		PackedCollection weights = new PackedCollection(shape(n));
		PackedCollection out = new PackedCollection(shape(1));

		weights.fill(pos -> 1.0);

		// Member 1 (Metal): written = source
		Assignment<MemoryData> write = new Assignment<>(n,
				() -> (Evaluable<MemoryData>) args -> written,
				() -> (Evaluable<MemoryData>) args -> source,
				List.of(ComputeRequirement.MTL));

		// Member 2 (CPU): out = sum(isolated multiply reading the Metal-written buffer).
		// The isolation forces the multiply to be evaluated as a nested dispatch during
		// member 2's argument preparation rather than inlined into its kernel.
		CollectionProducer mul = multiply(
				traverseEach(p(weights)), traverseEach(p(written)));
		Producer<PackedCollection> isolated =
				new CollectionProducerComputation.IsolatedProcess(mul);
		CollectionProducer reduce = sum(traverse(0, isolated));

		Assignment<MemoryData> gather = new Assignment<>(1,
				() -> (Evaluable<MemoryData>) args -> out,
				(Producer) reduce,
				List.of(ComputeRequirement.CPU));

		OperationList list = new OperationList("crossContextArgumentObservesPriorMemberWrite");
		list.add(write);
		list.add(gather);
		list.add(() -> () -> { });

		Runnable run = list.get();
		log("crossContextArgumentObservesPriorMemberWrite runnable=" + run.getClass().getSimpleName());

		if (run instanceof OperationListRunner) {
			List<Runnable> ops = ((OperationListRunner) run).getOperations();
			for (int i = 0; i < ops.size(); i++) {
				log("crossContextArgumentObservesPriorMemberWrite member=" + i +
						" type=" + ops.get(i).getClass().getSimpleName());
			}
		}

		for (int pass = 1; pass <= 3; pass++) {
			double base = pass * 10.0;
			double expected = 0.0;

			for (int i = 0; i < n; i++) {
				source.setMem(i, base + i);
				expected += base + i;
			}

			run.run();
			double result = out.toDouble(0);
			log("crossContextArgumentObservesPriorMemberWrite pass=" + pass +
					" result=" + result + " expected=" + expected);
			assertEquals(expected, result);
		}
	}
}
