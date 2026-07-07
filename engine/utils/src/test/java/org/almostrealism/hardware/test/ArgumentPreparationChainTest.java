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
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

/**
 * Verifies that argument preparation for an {@link org.almostrealism.hardware.AcceleratedOperation}
 * respects the {@link io.almostrealism.streams.Semaphore} chain: an argument evaluation that is
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
 *
 * <p>The composite runs at two sizes: small enough for any synchronous fast path to hide the
 * ordering question entirely, and large enough that the Metal dispatch is certainly a genuine
 * asynchronous encode. Commit counts from the {@link MetalCommandRunner} are logged around each
 * pass so a run records <em>when</em> the writing dispatch's buffer was committed relative to the
 * reading member.</p>
 */
public class ArgumentPreparationChainTest extends TestSuiteBase implements TestFeatures {

	/**
	 * Runs the composite, requiring member two's isolated argument to observe
	 * member one's write on every pass. The commit counts logged with each pass
	 * confirm the Metal write is a genuine asynchronous encode (committed only
	 * by a chained wait), so the ordering under test is real.
	 */
	@Test(timeout = 120000)
	public void crossContextArgumentObservesPriorMemberWrite() {
		runCrossContextComposite(8, "crossContext");
	}

	/**
	 * Runs the two-member cross-context composite several times with fresh values
	 * each pass and requires member two's isolated argument to observe member one's
	 * write on every pass.
	 *
	 * @param n     element count for the written buffer
	 * @param label log label for this variant
	 */
	private void runCrossContextComposite(int n, String label) {
		MetalComputeContext metal = Hardware.getLocalHardware()
				.getComputeContexts(false, true, ComputeRequirement.MTL).stream()
				.filter(MetalComputeContext.class::isInstance)
				.map(MetalComputeContext.class::cast)
				.findFirst().orElse(null);

		if (metal == null) {
			log(label + " skipping, no MetalComputeContext available");
			return;
		}

		MetalCommandRunner runner = metal.getCommandRunner();

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

		// Compile each member under its own pushed requirements: constructor
		// requirements apply at dispatch, but context selection happens at
		// compile time, so an unpushed compile would target the default context.
		OperationList list = new OperationList("crossContextArgumentObservesPriorMemberWrite");
		list.add(() -> compiled(write, ComputeRequirement.MTL));
		list.add(() -> compiled(gather, ComputeRequirement.CPU));

		Runnable run = list.get();
		log(label + " runnable=" + run.getClass().getSimpleName());

		if (run instanceof OperationListRunner) {
			List<Runnable> ops = ((OperationListRunner) run).getOperations();
			for (int i = 0; i < ops.size(); i++) {
				log(label + " member=" + i + " type=" + ops.get(i).getClass().getSimpleName());
			}
		}

		for (int pass = 1; pass <= 3; pass++) {
			double base = pass * 10.0;
			double expected = 0.0;

			double[] values = new double[n];
			for (int i = 0; i < n; i++) {
				values[i] = base + (i % 100);
				expected += values[i];
			}
			source.setMem(0, values, 0, n);

			long commits = runner.getCommitCount();
			long hostCommits = runner.getHostCompleteCommitCount();

			run.run();
			double result = out.toDouble(0);

			log(label + " pass=" + pass + " result=" + result + " expected=" + expected +
					" commits=" + (runner.getCommitCount() - commits) +
					" hostCompleteCommits=" + (runner.getHostCompleteCommitCount() - hostCommits));
			assertEquals(expected, result);
		}
	}

	/**
	 * Compiles the given assignment for the specified backend by pushing the
	 * requirement around compilation, mirroring how framework code selects a
	 * compute context at compile time.
	 *
	 * @param assignment  the assignment to compile
	 * @param requirement backend the assignment must compile for
	 * @return the compiled runnable
	 */
	private static Runnable compiled(Assignment<MemoryData> assignment, ComputeRequirement requirement) {
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(requirement));

		try {
			return assignment.get();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}
	}
}
