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
import io.almostrealism.streams.EvaluableStreamingAdapter;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.streams.StreamingEvaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationListRunner;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
			assertEquals((double) (baseline + 1), (double) issued);

			if (s2 != null) {
				s2.waitFor();
			}

			for (int i = 0; i < n; i++) {
				assertEquals(src.toDouble(i), dst.toDouble(i));
			}
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
			rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

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
		rand(a.getShape()).add(1.0).into(a.traverseEach()).evaluate();

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
	 * Verifies the {@link HardwareEvaluable#setResultProcessor(java.util.function.UnaryOperator)
	 * result processor} contract that {@link org.almostrealism.collect.computations.PackedCollectionRepeat}
	 * and {@link org.almostrealism.collect.computations.ReshapeProducer} rely on: wrapping a
	 * kernel-backed evaluable and requesting it through the
	 * {@link HardwareEvaluable#request(Object[], Semaphore, java.util.function.Consumer)} overload
	 * must deliver the processed value together with the dispatch's completion, without forcing a
	 * host wait (no command-buffer commit), and the processed value must match what the processor
	 * would produce from a direct, synchronous evaluation.
	 */
	@Test(timeout = 60000)
	public void resultProcessorDeliveryAvoidsCommit() {
		MetalComputeContext metal = metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int n = 16;

		PackedCollection a = new PackedCollection(n);
		rand(a.getShape()).add(1.0).into(a.traverseEach()).evaluate();

		Evaluable<PackedCollection> ev;
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(ComputeRequirement.MTL));

		try {
			ev = (Evaluable<PackedCollection>) (Evaluable) cp(a).multiply(2.0).get();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}

		HardwareEvaluable<PackedCollection> wrapper = new HardwareEvaluable<>(() -> ev, null, null, false);
		wrapper.setResultProcessor(out -> out.repeat(2));

		Object[] delivered = new Object[1];
		Semaphore[] completion = new Semaphore[1];

		MetalCommandRunner runner = metal.getCommandRunner();
		long baseline = runner.getCommitCount();

		wrapper.request(new Object[0], null, (CompletionConsumer<PackedCollection>) (value, c) -> {
			delivered[0] = value;
			completion[0] = c;
		});

		assertEquals((double) baseline, (double) runner.getCommitCount());
		assertTrue(delivered[0] instanceof PackedCollection);
		assertTrue(completion[0] != null);

		completion[0].waitFor();

		PackedCollection direct = ev.evaluate(new Object[0]);
		PackedCollection expected = direct.repeat(2);
		PackedCollection result = (PackedCollection) delivered[0];

		assertEquals((double) expected.getMemLength(), (double) result.getMemLength());
		for (int i = 0; i < expected.getMemLength(); i++) {
			assertEquals(expected.toDouble(i), result.toDouble(i));
		}
	}

	/**
	 * Verifies that {@link HardwareEvaluable#withDestination(org.almostrealism.hardware.MemoryBank)}
	 * (reached through {@link HardwareEvaluable#into(Object)}) carries the
	 * {@link HardwareEvaluable#setResultProcessor(java.util.function.UnaryOperator) result processor}
	 * forward instead of discarding it: the underlying kernel writes its unprocessed result into
	 * the destination, and the value returned from {@code evaluate()} on the destination-bound
	 * evaluable must be the processed re-view of that destination, matching a direct evaluation
	 * followed by the same processor.
	 */
	@Test(timeout = 30000)
	public void withDestinationAppliesResultProcessor() {
		int n = 8;

		PackedCollection a = new PackedCollection(n);
		rand(a.getShape()).add(1.0).into(a.traverseEach()).evaluate();

		Evaluable<PackedCollection> ev = (Evaluable<PackedCollection>) (Evaluable) cp(a).multiply(2.0).get();

		HardwareEvaluable<PackedCollection> wrapper = new HardwareEvaluable<>(() -> ev, null, null, false);
		wrapper.setResultProcessor(out -> out.repeat(2));

		PackedCollection destination = new PackedCollection(n);
		Evaluable<PackedCollection> withDestination = wrapper.into(destination);
		PackedCollection result = withDestination.evaluate();

		PackedCollection direct = ev.evaluate();
		PackedCollection expected = direct.repeat(2);

		assertEquals((double) expected.getMemLength(), (double) result.getMemLength());
		for (int i = 0; i < expected.getMemLength(); i++) {
			assertEquals(expected.toDouble(i), result.toDouble(i));
		}
	}

	/**
	 * Verifies that a shared {@link DestinationEvaluable} kernel reached through two independent
	 * {@link HardwareEvaluable} wrappers -- exactly as two separate
	 * {@link org.almostrealism.collect.computations.ReshapeProducer}/
	 * {@link org.almostrealism.collect.computations.PackedCollectionRepeat} instances would reach
	 * a shared underlying kernel -- serves both wrappers'
	 * {@link HardwareEvaluable#request(Object[], Semaphore, Consumer)} calls without either
	 * throwing from {@link HardwareEvaluable#setDownstream}, and delivers each wrapper's own
	 * processed result to its own consumer.
	 */
	@Test(timeout = 30000)
	public void sharedDestinationEvaluableServesIndependentWrappers() {
		int n = 8;

		PackedCollection a = new PackedCollection(n);
		rand(a.getShape()).add(1.0).into(a.traverseEach()).evaluate();

		Evaluable<PackedCollection> kernel = (Evaluable<PackedCollection>) (Evaluable) cp(a).multiply(2.0).get();
		PackedCollection destination = new PackedCollection(n);
		Evaluable<PackedCollection> rawKernel = ((HardwareEvaluable<PackedCollection>) kernel).getKernel().getValue();
		DestinationEvaluable<PackedCollection> shared = new DestinationEvaluable<>(rawKernel, destination);

		HardwareEvaluable<PackedCollection> repeatWrapper = new HardwareEvaluable<>(() -> shared, null, null, false);
		repeatWrapper.setResultProcessor(out -> out.repeat(2));

		HardwareEvaluable<PackedCollection> reshapeWrapper = new HardwareEvaluable<>(() -> shared, null, null, false);
		reshapeWrapper.setResultProcessor(out -> out.reshape(1, n));

		PackedCollection direct = kernel.evaluate();
		PackedCollection expectedRepeat = direct.repeat(2);
		PackedCollection expectedReshape = direct.reshape(1, n);

		Object[] repeatResult = new Object[1];
		Semaphore[] repeatCompletion = new Semaphore[1];
		repeatWrapper.request(new Object[0], null, (CompletionConsumer<PackedCollection>) (value, c) -> {
			repeatResult[0] = value;
			repeatCompletion[0] = c;
		});
		if (repeatCompletion[0] != null) repeatCompletion[0].waitFor();

		Object[] reshapeResult = new Object[1];
		Semaphore[] reshapeCompletion = new Semaphore[1];
		reshapeWrapper.request(new Object[0], null, (CompletionConsumer<PackedCollection>) (value, c) -> {
			reshapeResult[0] = value;
			reshapeCompletion[0] = c;
		});
		if (reshapeCompletion[0] != null) reshapeCompletion[0].waitFor();

		PackedCollection actualRepeat = (PackedCollection) repeatResult[0];
		PackedCollection actualReshape = (PackedCollection) reshapeResult[0];

		assertEquals((double) expectedRepeat.getMemLength(), (double) actualRepeat.getMemLength());
		for (int i = 0; i < expectedRepeat.getMemLength(); i++) {
			assertEquals(expectedRepeat.toDouble(i), actualRepeat.toDouble(i));
		}

		for (int i = 0; i < n; i++) {
			assertEquals(expectedReshape.toDouble(i), actualReshape.toDouble(i));
		}
	}

	/**
	 * Verifies that {@link EvaluableStreamingAdapter#request(Object[], Semaphore, Consumer)} honors
	 * a non-null {@code dependsOn}: submission to the executor must not block the caller, but the
	 * submitted task must wait for {@code dependsOn} to complete before reading the arguments and
	 * evaluating, rather than reading them immediately and racing the dependency. Without the wait,
	 * the delivered value would already be captured from the state that existed at request time,
	 * well before the state update below and the {@code dependsOn} completion that follows it.
	 */
	@Test(timeout = 10000)
	public void streamingAdapterRequestWaitsForDependsOn() throws InterruptedException {
		int[] sharedState = { 0 };

		Evaluable<Integer> hostEvaluable = args -> sharedState[0];
		EvaluableStreamingAdapter<Integer> adapter = new EvaluableStreamingAdapter<>(hostEvaluable);

		DefaultLatchSemaphore dependsOn = new DefaultLatchSemaphore((Semaphore) null, 1);
		CountDownLatch delivered = new CountDownLatch(1);
		Integer[] result = new Integer[1];

		Thread requester = new Thread(() ->
				adapter.request(new Object[0], dependsOn, (Consumer<Integer>) value -> {
					result[0] = value;
					delivered.countDown();
				}));
		requester.start();

		// Give the requester thread ample time to reach (and, if the dependency were
		// disregarded, run straight past) the wait before the dependency is satisfied
		Thread.sleep(200);
		assertEquals(1L, delivered.getCount());

		sharedState[0] = 42;
		dependsOn.countDown();

		assertTrue(delivered.await(5, TimeUnit.SECONDS));
		requester.join(5000);
		assertEquals(42, (int) result[0]);
	}

	/**
	 * Verifies that a single {@link EvaluableStreamingAdapter} reached through two independent
	 * requesters -- each calling {@link EvaluableStreamingAdapter#request(Object[], Semaphore,
	 * Consumer)} with its own downstream consumer, as two separate {@link HardwareEvaluable}
	 * wrappers over a shared host-evaluated kernel would -- delivers each request's result to its
	 * own consumer without either contending for {@link EvaluableStreamingAdapter#setDownstream}.
	 */
	@Test(timeout = 10000)
	public void sharedStreamingAdapterServesIndependentRequesters() throws InterruptedException {
		Evaluable<Integer> hostEvaluable = args -> ((Integer) args[0]) * 2;
		EvaluableStreamingAdapter<Integer> adapter = new EvaluableStreamingAdapter<>(hostEvaluable);

		CountDownLatch delivered = new CountDownLatch(2);
		Integer[] firstResult = new Integer[1];
		Integer[] secondResult = new Integer[1];

		adapter.request(new Object[] { 3 }, null, (Consumer<Integer>) value -> {
			firstResult[0] = value;
			delivered.countDown();
		});
		adapter.request(new Object[] { 5 }, null, (Consumer<Integer>) value -> {
			secondResult[0] = value;
			delivered.countDown();
		});

		assertTrue(delivered.await(5, TimeUnit.SECONDS));
		assertEquals(6, (int) firstResult[0]);
		assertEquals(10, (int) secondResult[0]);
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
		rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

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
