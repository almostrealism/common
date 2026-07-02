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

import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Isolated experiment confirming that {@link Assignment} kernels can be sequenced with an
 * <em>explicit chain of {@link Semaphore}s</em> via {@link Submittable#submit(Semaphore)}, rather
 * than each blocking the host (or the {@code ComputeContext} executor) on its own completion.
 *
 * <p>This is the foundational mechanism for letting {@code AcceleratedOperation}'s aggregated
 * copy-in/copy-out become genuine kernel programs that can be encoded onto a single Metal command
 * buffer alongside the kernel they wrap. Before changing {@code AcceleratedOperation} to do that
 * internally, this test verifies the sequencing primitive in isolation: each operation is dispatched
 * with {@code submit(dependsOn)} where {@code dependsOn} is the previous operation's completion
 * semaphore, and a single completion wait is taken at the top of the call stack.</p>
 *
 * <h2>Why the result is decisive</h2>
 *
 * <p>The three kernels form a read-after-write chain through zero-initialized buffers:
 * {@code mid1 = src}, then {@code mid2 = mid1}, then {@code dst = mid2}. If the semaphore chain did
 * <em>not</em> serialize them — i.e. if a later kernel were allowed to read its input before the
 * earlier kernel wrote it — the later kernel would read zeros and {@code dst} would not equal
 * {@code src}. Correct, in-order results therefore confirm the chain enforced ordering inside the
 * provider without a per-operation host wait.</p>
 *
 * <p>Argument aggregation is disabled for the duration of the test so that each copy is a plain
 * kernel whose {@code submit()} genuinely defers completion (aggregation with a {@code null} output
 * forces a per-operation copy-back wait, which would mask the deferral being demonstrated here).</p>
 */
public class AssignmentSemaphoreChainTest extends TestSuiteBase {

	/**
	 * Chains three copy {@link Assignment}s with an explicit semaphore dependency between each and
	 * verifies the final buffer matches the source — proving non-blocking, correctly-ordered
	 * sequencing.
	 */
	@Test(timeout = 60000)
	public void chainedCopyAssignments() {
		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			int n = 16;

			PackedCollection src = new PackedCollection(n);
			PackedCollection mid1 = new PackedCollection(n);
			PackedCollection mid2 = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);

			// Nonzero source so that a stale (zero) read anywhere in the chain is detectable.
			src.fill(pos -> Math.random() + 1.0);

			// Three pure-copy kernels forming a read-after-write chain:
			//   mid1 = src ; mid2 = mid1 ; dst = mid2
			Submittable op1 = copyKernel(src, mid1, n);
			Submittable op2 = copyKernel(mid1, mid2, n);
			Submittable op3 = copyKernel(mid2, dst, n);

			// Explicit Semaphore chain. Each submit() issues the dispatch (encoding it after the
			// prior one) and returns this operation's completion without blocking the host; the next
			// operation depends on it inside the provider.
			Semaphore s1 = op1.submit(null);
			Semaphore s2 = op2.submit(s1);
			Semaphore s3 = op3.submit(s2);

			log("semaphores: s1=" + s1 + " s2=" + s2 + " s3=" + s3);

			// Single completion wait at the top of the call stack (a legitimate place to block).
			if (s3 != null) {
				s3.waitFor();
			}

			for (int i = 0; i < n; i++) {
				assertEquals(src.toDouble(i), dst.toDouble(i));
			}
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Builds a compiled, {@link Submittable} copy kernel that assigns {@code from} into {@code to}.
	 *
	 * <p>The source and target are wrapped as plain lambda {@link io.almostrealism.relation.Producer}s
	 * (not {@link io.almostrealism.relation.Provider}s) so that {@link Assignment#get()} compiles the
	 * assignment {@link io.almostrealism.scope.Scope} into a genuine kernel — the compiled
	 * {@code AcceleratedComputationOperation} is the {@link Submittable} returned here — instead of
	 * taking the provider short-circuit to a {@code DestinationEvaluable} (which is not submittable).</p>
	 *
	 * @param from source buffer to copy from
	 * @param to   destination buffer to copy into
	 * @param len  number of elements to copy
	 * @return the compiled, submittable copy kernel
	 */
	private static Submittable copyKernel(PackedCollection from, PackedCollection to, int len) {
		Assignment<MemoryData> assign = new Assignment<>(len,
				() -> (Evaluable<MemoryData>) args -> to,
				() -> (Evaluable<MemoryData>) args -> from);
		return (Submittable) assign.get();
	}
}
