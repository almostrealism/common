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

package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrow microbenchmark isolating <em>per-dispatch overhead</em> for a list of many
 * small, independent kernel operations.
 *
 * <p>This is the reference harness for the submission-batching and deferred-completion
 * work for sustained kernel dispatch. It
 * deliberately avoids the complexity of the real-time audio pipeline: it builds an
 * {@link OperationList} of {@code N} tiny element-wise operations, each of which compiles
 * to its own kernel and is dispatched separately (the {@code false} argument to
 * {@link OperationList#OperationList(String, boolean)} keeps the operations from being
 * fused into a single kernel — kernel fusion is a separate concern and is
 * intentionally out of scope here).</p>
 *
 * <h2>The seam being measured</h2>
 *
 * <p>Today every operation in the list blocks on its own native completion inside
 * {@code run()}, so a list of {@code N} operations is {@code N} synchronous round-trips per
 * iteration. The goal of the batching work is to let a
 * {@link io.almostrealism.code.ComputeContext} submit the whole group with a single
 * provider-level submission and defer completion to a single barrier at the end — moving
 * the wait <em>down into the provider</em>. (The deferred-wait barrier will be reached
 * <em>explicitly</em> via the executable's completion handle, not implicitly through a
 * thread-local — that thread-local "current semaphore" anti-pattern has been removed.)</p>
 *
 * <p>Provider mechanisms:</p>
 * <ul>
 *   <li><strong>Metal</strong>: one {@code MTLCommandBuffer} for the group, committed once.</li>
 *   <li><strong>OpenCL</strong>: chain kernels with {@code cl_event} wait-lists (already
 *       prototyped in {@code CLOperator} via {@code CLSemaphore}, currently disabled), one
 *       {@code clFinish} at the barrier.</li>
 *   <li><strong>Native (CPU)</strong>: no batching needed — runs sequentially.</li>
 * </ul>
 *
 * <p>Measured as microseconds per dispatch, the smaller each operation is the more the
 * number reflects pure submission/synchronization overhead rather than compute. Run the
 * same test under different backends (via {@code AR_HARDWARE_DRIVER=mtl|cl|native}) to
 * compare per-dispatch overhead before and after the batching work.</p>
 *
 * @see OperationSemaphoreTests
 */
public class OperationDispatchBatchingTests extends TestSuiteBase {

	/**
	 * Many tiny operations: per-dispatch overhead dominates, so this is the most
	 * sensitive measure of submission/synchronization cost.
	 */
	@Test(timeout = 4 * 60000)
	public void manySmallDispatches() {
		// 32 ops x 32 iterations = 1024 dispatches — enough to measure overhead while
		// staying below the ~2300-dispatch point where the current Metal backend stalls
		// (raise the iteration count to reproduce that stall on Metal).
		measure(32, 1, 4, 32);
	}

	/**
	 * Regression guard for a Metal command-buffer completion bug. A list of independent operations
	 * whose arguments are aggregated (tiny memory reservations) commits one Metal command buffer per
	 * operation. A defect in {@code MetalCommandRunner}'s Objective-C autorelease handling — it
	 * pushed an autorelease pool per open command buffer and popped it at commit, so the pool spanned
	 * the runner's separate encode and commit/await executor tasks — wedged the <em>second</em> such
	 * command buffer's completion forever in {@code MTL.waitUntilCompleted}, hanging the whole run. A
	 * single operation always completed; two were the minimal trigger. The fix wraps each executor
	 * task in its own autorelease pool and retains the command buffer explicitly across tasks (see
	 * {@code MetalCommandRunner.runInPool} and {@code MTLCommandBuffer.release}). This test is small
	 * and fast on purpose, and its short timeout makes any recurrence fail fast instead of hanging
	 * the suite. (Only meaningful on the Metal backend; on synchronous backends it passes trivially.)
	 */
	@Test(timeout = 60000)
	public void independentDispatchesComplete() {
		// Tiny reservations (count*dim = 4 elements, below the off-heap threshold) force argument
		// aggregation, which correctly commits one command buffer per operation instead of batching
		// — i.e. this deliberately exercises the multi-command-buffer path that regressed. A warmup
		// run plus one timed iteration of four operations is more than enough buffers to retrigger
		// the wedge if it ever returns; measure() asserts each output equals twice its input.
		measure(4, 1, 4, 1);
	}

	/**
	 * Runs an {@link OperationList} of {@code ops} independent element-wise operations
	 * (each output element = input element + input element) {@code iterations} times and
	 * reports the per-dispatch wall-clock overhead.
	 *
	 * @param ops        number of independent operations (separate kernel dispatches)
	 * @param count      outer (traversed) dimension of each operation's collections
	 * @param dim        inner dimension of each operation's collections
	 * @param iterations number of times the whole list is run and waited on
	 */
	protected void measure(int ops, int count, int dim, int iterations) {
		OperationList op = new OperationList("dispatch batching example", false);

		List<PackedCollection> inputs = new ArrayList<>();
		List<PackedCollection> outputs = new ArrayList<>();

		for (int i = 0; i < ops; i++) {
			PackedCollection input = new PackedCollection(count, dim);
			PackedCollection output = new PackedCollection(count, dim);
			input.fill(pos -> Math.random());

			op.add(a("doubling " + i, traverseEach(p(output)),
					add(traverseEach(p(input)), traverseEach(p(input)))));

			inputs.add(input);
			outputs.add(output);
		}

		Runnable r = op.get();

		// Warm up (compilation, first-run allocation) before timing.
		r.run();

		long start = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			r.run();
		}
		long elapsedNanos = System.nanoTime() - start;

		long dispatches = (long) ops * iterations;
		double usPerDispatch = elapsedNanos / 1000.0 / dispatches;
		log(ops + " ops x " + iterations + " iterations = " + dispatches + " dispatches in "
				+ (elapsedNanos / 1_000_000L) + "ms (" + String.format("%.2f", usPerDispatch)
				+ " us/dispatch)");

		// Correctness sanity: each output element should equal twice its input.
		for (int n = 0; n < ops; n++) {
			for (int i = 0; i < count; i++) {
				for (int j = 0; j < dim; j++) {
					assertEquals(2.0 * inputs.get(n).valueAt(i, j), outputs.get(n).valueAt(i, j));
				}
			}
		}
	}
}
