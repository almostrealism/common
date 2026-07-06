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
import io.almostrealism.concurrent.CompletionConsumer;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.streams.StreamingEvaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationListRunner;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

/**
 * Validates the two guarantees that make {@link Semaphore} chaining safe to use everywhere,
 * so that internal machinery can always thread a {@code dependsOn} through
 * {@link Submittable#submit(Semaphore)} instead of blocking the host:
 *
 * <ul>
 *   <li>Chaining dispatches on one Metal runner does <em>not</em> defeat command-buffer
 *   batching — a dependency still in the open buffer is ordered by in-buffer hazard
 *   tracking and costs no commit ({@link #chainedMetalDispatchesShareCommandBuffer()}).</li>
 *   <li>{@link OperationListRunner} orders members across {@link io.almostrealism.code.ComputeContext}s
 *   by threading each member's completion into the next member's
 *   {@link Submittable#submit(Semaphore)} ({@link #mixedContextListOrdering()}) — the
 *   regression case where a Metal producer's uncommitted work was read by a synchronous
 *   native consumer.</li>
 * </ul>
 */
public class SemaphoreChainBatchingTest extends TestSuiteBase {

	/**
	 * Chains three Metal copy kernels via explicit {@link Submittable#submit(Semaphore)} and
	 * verifies that issuing the chain performs no command-buffer commits (the dependencies are
	 * in-buffer and therefore free) while the final wait commits exactly once and yields
	 * correct, fully-ordered results.
	 */
	@Test(timeout = 60000)
	public void chainedMetalDispatchesShareCommandBuffer() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			int n = 16;

			PackedCollection src = new PackedCollection(n);
			PackedCollection mid = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);
			src.fill(pos -> Math.random() + 1.0);

			Submittable op1 = copyKernel(src, mid, n, ComputeRequirement.MTL);
			Submittable op2 = copyKernel(mid, dst, n, ComputeRequirement.MTL);

			MetalCommandRunner runner = metal.getCommandRunner();
			long baseline = runner.getCommitCount();

			Semaphore s1 = op1.submit(null);
			Semaphore s2 = op2.submit(s1);

			long issued = runner.getCommitCount();
			assertEquals((double) baseline, (double) issued);

			if (s2 != null) {
				s2.waitFor();
			}

			for (int i = 0; i < n; i++) {
				assertEquals(src.toDouble(i), dst.toDouble(i));
			}

			assertEquals((double) (baseline + 1), (double) runner.getCommitCount());
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Verifies the non-blocking foreign-dependency bridge: submitting a Metal dispatch that
	 * depends on a {@link Semaphore} from outside the runner must return while that dependency
	 * is still outstanding (the runner encodes a GPU wait on a host-signaled event rather than
	 * blocking; see {@link MetalCommandRunner#enableHostSignaledBridges}), must not force a
	 * commit, and must produce correct results once the dependency completes. Before the
	 * bridge existed this call blocked until the foreign dependency completed, which under
	 * this test's ordering (completion arrives only after submit returns) was a deadlock —
	 * the timeout guards that regression.
	 */
	@Test(timeout = 60000)
	public void foreignDependencyBridgesWithoutHostWait() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			int n = 16;

			PackedCollection src = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);
			src.fill(pos -> Math.random() + 1.0);

			Submittable op = copyKernel(src, dst, n, ComputeRequirement.MTL);

			MetalCommandRunner runner = metal.getCommandRunner();
			long baseline = runner.getCommitCount();

			DefaultLatchSemaphore foreign = new DefaultLatchSemaphore(
					new OperationMetadata("foreignWork", "foreign dependency for bridge test"), 1);

			Semaphore s = op.submit(foreign);

			// The dispatch was encoded while the foreign dependency is still outstanding,
			// with no commit forced
			assertEquals((double) baseline, (double) runner.getCommitCount());

			foreign.countDown();
			s.waitFor();

			for (int i = 0; i < n; i++) {
				assertEquals(src.toDouble(i), dst.toDouble(i));
			}
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Verifies commit-cause attribution: a host wait that forces a commit increments
	 * {@link MetalCommandRunner#getHostCompleteCommitCount()} and records the requesting
	 * operation in {@link MetalCommandRunner#hostCompleteRequesters}, while a repeated wait
	 * on the same (already completed) dispatch is free and attributes nothing.
	 */
	@Test(timeout = 60000)
	public void hostCompleteCommitAttribution() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		boolean aggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			int n = 16;

			PackedCollection src = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);
			src.fill(pos -> Math.random() + 1.0);

			Submittable op = copyKernel(src, dst, n, ComputeRequirement.MTL);

			MetalCommandRunner runner = metal.getCommandRunner();
			long baseTotal = runner.getCommitCount();
			long baseHost = runner.getHostCompleteCommitCount();
			int baseAttributed = attributedWaits();

			Semaphore s = op.submit(null);
			assertEquals((double) baseHost, (double) runner.getHostCompleteCommitCount());

			s.waitFor();
			assertEquals((double) (baseTotal + 1), (double) runner.getCommitCount());
			assertEquals((double) (baseHost + 1), (double) runner.getHostCompleteCommitCount());
			assertEquals((double) (baseAttributed + 1), (double) attributedWaits());

			s.waitFor();
			assertEquals((double) (baseHost + 1), (double) runner.getHostCompleteCommitCount());
			assertEquals((double) (baseAttributed + 1), (double) attributedWaits());
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = aggregation;
		}
	}

	/**
	 * Verifies the asynchronous argument delivery contract: when a streaming producer's
	 * downstream is a {@link CompletionConsumer}, {@link StreamingEvaluable#request} delivers
	 * the destination together with the dispatch's completion {@link Semaphore} without any
	 * host wait — so issuing the request performs no command-buffer commit — and the contents
	 * are valid once the delivered completion is waited.
	 */
	@Test(timeout = 60000)
	public void completionConsumerDeliveryAvoidsCommit() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int n = 16;

		PackedCollection a = new PackedCollection(n);
		a.fill(pos -> Math.random() + 1.0);

		Evaluable<PackedCollection> ev;
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(ComputeRequirement.MTL));

		try {
			ev = (Evaluable<PackedCollection>) (Evaluable) cp(a).multiply(2.0).get();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}

		PackedCollection destination = new PackedCollection(n);
		StreamingEvaluable<PackedCollection> streaming =
				(StreamingEvaluable<PackedCollection>) ev.into(destination);

		Object[] delivered = new Object[1];
		Semaphore[] completion = new Semaphore[1];
		streaming.setDownstream((CompletionConsumer<PackedCollection>) (value, c) -> {
			delivered[0] = value;
			completion[0] = c;
		});

		MetalCommandRunner runner = metal.getCommandRunner();
		long baseline = runner.getCommitCount();

		streaming.request(new Object[0]);

		assertEquals((double) baseline, (double) runner.getCommitCount());
		assertTrue(delivered[0] instanceof PackedCollection);
		assertTrue(completion[0] != null);

		completion[0].waitFor();

		PackedCollection result = (PackedCollection) delivered[0];

		for (int i = 0; i < n; i++) {
			assertEquals(2.0 * a.toDouble(i), result.toDouble(i));
		}
	}

	/**
	 * Returns the total number of commit-forcing host waits recorded in
	 * {@link MetalCommandRunner#hostCompleteRequesters} across all requesters.
	 */
	private static int attributedWaits() {
		return MetalCommandRunner.hostCompleteRequesters.getCounts()
				.values().stream().mapToInt(Integer::intValue).sum();
	}

	/**
	 * Runs an {@link OperationListRunner} whose first member is a Metal copy kernel and whose
	 * second member is a CPU copy kernel reading the first member's output. Without the pending
	 * semaphore threaded between members, the synchronous native consumer reads the Metal
	 * producer's destination before its command buffer commits and sees stale zeros; with the
	 * chain in place the result matches the source.
	 */
	@Test(timeout = 60000)
	public void mixedContextListOrdering() {
		if (metalContext() == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int n = 16;

		PackedCollection src = new PackedCollection(n);
		PackedCollection temp = new PackedCollection(n);
		PackedCollection dst = new PackedCollection(n);
		src.fill(pos -> Math.random() + 1.0);

		Runnable op1 = (Runnable) copyKernel(src, temp, n, ComputeRequirement.MTL);
		Runnable op2 = (Runnable) copyKernel(temp, dst, n, ComputeRequirement.CPU);

		OperationListRunner runner = new OperationListRunner(
				new OperationMetadata("mixedContextChain", "mixed-context semaphore chain"),
				List.of(op1, op2), null, null);
		runner.run();

		for (int i = 0; i < n; i++) {
			assertEquals(src.toDouble(i), dst.toDouble(i));
		}
	}

	/**
	 * Returns the shared {@link MetalComputeContext}, or null when the current hardware
	 * configuration exposes no Metal backend (in which case these tests are skipped).
	 */
	static MetalComputeContext metalContext() {
		try {
			return Hardware.getLocalHardware()
					.getComputeContexts(false, true, ComputeRequirement.MTL).stream()
					.filter(MetalComputeContext.class::isInstance)
					.map(MetalComputeContext.class::cast)
					.findFirst().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Builds a compiled, {@link Submittable} copy kernel assigning {@code from} into {@code to},
	 * pinned to the backend selected by {@code requirement}. The operands are wrapped as plain
	 * lambda {@link io.almostrealism.relation.Producer}s so {@link Assignment#get()} compiles a
	 * genuine kernel rather than short-circuiting to a direct memory copy, and the requirement
	 * is active during compilation so the kernel's {@link io.almostrealism.code.ComputeContext}
	 * is chosen deterministically.
	 *
	 * @param from        source buffer to copy from
	 * @param to          destination buffer to copy into
	 * @param len         number of elements to copy
	 * @param requirement backend the kernel must compile for
	 * @return the compiled, submittable copy kernel
	 */
	private static Submittable copyKernel(PackedCollection from, PackedCollection to, int len,
										  ComputeRequirement requirement) {
		Assignment<MemoryData> assign = new Assignment<>(len,
				() -> (Evaluable<MemoryData>) args -> to,
				() -> (Evaluable<MemoryData>) args -> from,
				List.of(requirement));

		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(requirement));

		try {
			return (Submittable) assign.get();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}
	}
}
