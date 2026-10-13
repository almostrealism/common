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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.streams.StreamingEvaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.OperationListRunner;
import org.almostrealism.hardware.mem.MemoryDataCopy;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Verifies that a {@link MemoryDataCopy} that is a member of an {@link OperationList} is
 * chained like the compiled kernels around it, instead of completing everything ahead of it,
 * and then itself, on the host.
 *
 * <p>The copies cover the two ways a copy's source is resolved without a host wait: a
 * {@link io.almostrealism.relation.Provider} of a collection, which is bound as a handle, and a
 * compiled computation, which is requested in order after the previous member and delivers
 * its result together with the completion of its dispatch. The results must still reflect the
 * order of the list, and on Metal the whole list must share one command buffer.</p>
 */
public class MemoryDataCopyChainingTest extends TestSuiteBase {
	/** Number of elements in each buffer. */
	private static final int SIZE = 16;

	/**
	 * Each member reads what the member before it writes, so the final result is only
	 * correct if every copy is ordered after the member before it and before the member
	 * after it. The copies must be issued as {@link Submittable} members.
	 */
	@Test(timeout = 60000)
	public void chainedCopiesAreOrdered() {
		PackedCollection src = new PackedCollection(SIZE);
		PackedCollection mid = new PackedCollection(SIZE);
		PackedCollection copied = new PackedCollection(SIZE);
		PackedCollection dst = new PackedCollection(SIZE);
		rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

		OperationListRunner runner = chain(src, mid, copied, dst, null);

		for (Runnable member : runner.getOperations()) {
			assertTrue(member.getClass().getSimpleName() + " must be Submittable",
					member instanceof Submittable);
		}

		runner.run();
		assertChained(src, dst);

		src.setMem(0, 10.0);
		runner.run();
		assertChained(src, dst);
	}

	/**
	 * On Metal, chaining the copies keeps the whole list in one command buffer: running the
	 * list commits exactly once, for the wait at its end. A copy that completed on the host
	 * committed once for everything issued ahead of it and once more for itself.
	 */
	@Test(timeout = 60000)
	public void chainedCopiesCommitOnce() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		Assume.assumeTrue("Requires a Metal compute context", metal != null);

		PackedCollection src = new PackedCollection(SIZE);
		PackedCollection mid = new PackedCollection(SIZE);
		PackedCollection copied = new PackedCollection(SIZE);
		PackedCollection dst = new PackedCollection(SIZE);
		rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

		OperationListRunner runner = chain(src, mid, copied, dst, List.of(ComputeRequirement.MTL));

		// The first run compiles and prepares every member
		runner.run();
		assertChained(src, dst);

		dst.clear();

		MetalCommandRunner commands = metal.getCommandRunner();
		long baseline = commands.getCommitCount();

		runner.run();
		assertEquals((double) (baseline + 1), (double) commands.getCommitCount());
		assertChained(src, dst);
	}

	/**
	 * A copy that is run by itself rather than chained has finished when {@link Runnable#run()}
	 * returns, whether its source is a handle or a computation.
	 */
	@Test(timeout = 60000)
	public void standaloneCopiesCompleteWhenRun() {
		PackedCollection src = new PackedCollection(SIZE);
		PackedCollection dst = new PackedCollection(SIZE);
		rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

		new MemoryDataCopy("standalone handle copy", p(src), p(dst), SIZE).get().run();
		for (int i = 0; i < SIZE; i++) {
			assertEquals(src.toDouble(i), dst.toDouble(i));
		}

		new MemoryDataCopy("standalone computed copy", cp(src).multiply(3.0), p(dst), SIZE).get().run();
		for (int i = 0; i < SIZE; i++) {
			assertEquals(3.0 * src.toDouble(i), dst.toDouble(i));
		}
	}

	/**
	 * With a profile attached, issuing a chained copy is recorded under the copy's own
	 * operation, as the run of a copy that could not be chained was, rather than under an
	 * anonymous entry created afresh for every call: repeated runs of the list must reuse the
	 * profile's nodes and accumulate time against each copy.
	 */
	@Test(timeout = 60000)
	public void profiledCopiesAreAttributedToTheirOperation() {
		PackedCollection src = new PackedCollection(SIZE);
		PackedCollection mid = new PackedCollection(SIZE);
		PackedCollection dst = new PackedCollection(SIZE);
		rand(src.getShape()).add(1.0).into(src.traverseEach()).evaluate();

		MemoryDataCopy first = new MemoryDataCopy("profiled src to mid", p(src), p(mid), SIZE);
		MemoryDataCopy second = new MemoryDataCopy("profiled mid to dst", p(mid), p(dst), SIZE);

		OperationList list = new OperationList("Profiled copies", false);
		list.add(first);
		list.add(second);

		OperationProfileNode profile = new OperationProfileNode("profiledCopies");
		Runnable runner = list.get(profile);
		runner.run();

		long nodes = profile.all().count();
		runner.run();
		runner.run();

		assertEquals((double) nodes, (double) profile.all().count());

		for (MemoryDataCopy copy : List.of(first, second)) {
			String key = OperationProfile.metadataKey(copy.getMetadata());
			assertTrue("The profile must have a node for " + copy.describe(),
					profile.getProfileNode(key).isPresent());
			assertTrue("Time must be recorded against " + copy.describe(),
					profile.getProfileNode(key).get().getMeasuredDuration() > 0);
		}

		for (int i = 0; i < SIZE; i++) {
			assertEquals(src.toDouble(i), dst.toDouble(i));
		}
	}

	/**
	 * A copy whose source is a raw supplier cannot order the supplier's own work, so the
	 * dependency it is submitted with must have completed before the supplier is resolved. The
	 * dependency here also produces the value being copied, so the copy must carry it.
	 */
	@Test(timeout = 60000)
	public void supplierSourceWaitsForDependency() {
		PackedCollection produced = new PackedCollection(1);
		PackedCollection dst = new PackedCollection(1);

		AtomicInteger waits = new AtomicInteger();
		AtomicBoolean resolvedAfterDependency = new AtomicBoolean();
		Semaphore dependsOn = () -> {
			waits.incrementAndGet();
			produced.setMem(0, 7.0);
		};

		MemoryDataCopy copy = new MemoryDataCopy("supplier copy", () -> {
			resolvedAfterDependency.set(waits.get() > 0);
			return produced;
		}, () -> dst, 1);

		Semaphore done = ((Submittable) copy.get()).submit(dependsOn);
		if (done != null) done.waitFor();

		assertEquals(1, waits.get());
		assertTrue("The supplier must be resolved after the dependency completes",
				resolvedAfterDependency.get());
		assertEquals(7.0, dst.toDouble(0));
	}

	/**
	 * A dispatch-backed source that can only deliver once its work has finished (as a kernel does
	 * while output monitoring must read its result) delivers on the dependency's callback thread,
	 * after waiting for it, rather than on the thread that requested it. The copy must wait for
	 * that late delivery without deadlocking, must not evaluate the source synchronously, and must
	 * copy the value the dependency produced.
	 */
	@Test(timeout = 60000)
	public void lateDeliveringSourceIsCopiedAfterDependency() {
		PackedCollection produced = new PackedCollection(1);
		PackedCollection dst = new PackedCollection(1);

		AtomicInteger waits = new AtomicInteger();
		Semaphore dependsOn = () -> {
			waits.incrementAndGet();
			produced.setMem(0, 11.0);
		};

		LateSource late = new LateSource(produced);
		Producer<PackedCollection> source = () -> late;
		MemoryDataCopy copy = new MemoryDataCopy("late source copy", source, p(dst), 1);

		Semaphore done = ((Submittable) copy.get()).submit(dependsOn);
		if (done != null) done.waitFor();

		assertEquals(1, late.requests.get());
		assertEquals(0, late.evaluations.get());
		assertTrue("The dependency must be waited for before the source is delivered", waits.get() > 0);
		assertEquals(11.0, dst.toDouble(0));
	}

	/**
	 * A copy whose target is a compiled evaluable that is not a fixed handle cannot know whether
	 * resolving it reads memory the pending work is still producing, so the dependency must have
	 * completed before the target is resolved, even though the source is a fixed handle.
	 */
	@Test(timeout = 60000)
	public void unfixedTargetWaitsForDependency() {
		PackedCollection src = new PackedCollection(1);
		PackedCollection dst = new PackedCollection(1);

		AtomicInteger waits = new AtomicInteger();
		AtomicBoolean resolvedAfterDependency = new AtomicBoolean();
		Semaphore dependsOn = () -> {
			waits.incrementAndGet();
			src.setMem(0, 5.0);
		};

		Evaluable<PackedCollection> target = args -> {
			resolvedAfterDependency.set(waits.get() > 0);
			return dst;
		};
		Producer<PackedCollection> targetProducer = () -> target;

		MemoryDataCopy copy = new MemoryDataCopy("unfixed target copy", p(src), targetProducer, 1);
		Semaphore done = ((Submittable) copy.get()).submit(dependsOn);
		if (done != null) done.waitFor();

		assertTrue("The target must be resolved after the dependency completes",
				resolvedAfterDependency.get());
		assertEquals(5.0, dst.toDouble(0));
	}

	/**
	 * Preferring a device context selects a non-CPU context that manages the memory whenever one
	 * does, even when a CPU context that delegates to the same provider is listed first (shared
	 * memory with both a native and a Metal context configured), and otherwise selects the same
	 * context as the plain lookup. Without the preference, the first managing context is chosen.
	 */
	@Test(timeout = 60000)
	public void preferredDeviceContextManagesMemory() {
		Hardware hardware = Hardware.getLocalHardware();
		assertNull(hardware.getComputeContext(null, true));

		PackedCollection collection = new PackedCollection(SIZE);
		Memory memory = collection.getMem();

		ComputeContext<MemoryData> device = null;
		for (DataContext<MemoryData> dc : hardware.getAllDataContexts()) {
			if (dc.getMemoryProviders().contains(memory.getProvider()) && !dc.getComputeContexts().isEmpty()
					&& !dc.getComputeContext().isCPU() && device == null) {
				device = dc.getComputeContext();
			}
		}

		ComputeContext<MemoryData> preferred = hardware.getComputeContext(memory, true);
		assertSame(hardware.getComputeContext(memory), hardware.getComputeContext(memory, false));

		if (device == null) {
			assertSame(hardware.getComputeContext(memory), preferred);
		} else {
			assertSame(device, preferred);
		}
	}

	/**
	 * A dispatch-backed {@link StreamingEvaluable} that, like a kernel whose output must be read
	 * before it is delivered, delivers its result only after {@code dependsOn} has completed, on
	 * that completion's callback thread.
	 */
	private static final class LateSource implements Evaluable<PackedCollection>, StreamingEvaluable<PackedCollection> {
		/** The value delivered by every request. */
		private final PackedCollection value;
		/** Number of requests received. */
		private final AtomicInteger requests = new AtomicInteger();
		/** Number of synchronous evaluations performed. */
		private final AtomicInteger evaluations = new AtomicInteger();

		/**
		 * Creates a source that delivers {@code value}.
		 *
		 * @param value the value to deliver
		 */
		private LateSource(PackedCollection value) {
			this.value = value;
		}

		@Override
		public PackedCollection evaluate(Object... args) {
			evaluations.incrementAndGet();
			return value;
		}

		@Override
		public void request(Object[] args, Semaphore dependsOn, Consumer<PackedCollection> downstream) {
			requests.incrementAndGet();
			Semaphore.onComplete(dependsOn, () -> downstream.accept(value));
		}

		@Override
		public void request(Object[] args, Semaphore dependsOn) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void setDownstream(Consumer<PackedCollection> consumer) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isDispatchBacked() {
			return true;
		}
	}

	/**
	 * Builds the uncompiled list {@code mid = 2 * src; copied = mid; dst = copied + 1}, where
	 * the second member is a copy whose source is a handle and the third is a copy whose source
	 * is a computation, and returns its runner.
	 *
	 * @param src          the buffer the first member reads
	 * @param mid          the buffer the first member writes and the first copy reads
	 * @param copied       the buffer the first copy writes and the second copy's source reads
	 * @param dst          the buffer the second copy writes
	 * @param requirements the compute requirements for the list, or {@code null} for none
	 * @return the runner that executes the members in order
	 */
	private OperationListRunner chain(PackedCollection src, PackedCollection mid,
									  PackedCollection copied, PackedCollection dst,
									  List<ComputeRequirement> requirements) {
		OperationList list = new OperationList("Chained copies", false);
		list.add(a(p(mid), cp(src).multiply(2.0)));
		list.add(new MemoryDataCopy("mid to copied", p(mid), p(copied), SIZE));
		list.add(new MemoryDataCopy("copied plus one to dst", cp(copied).add(1.0), p(dst), SIZE));

		if (requirements != null) {
			list.setComputeRequirements(requirements);
		}

		Runnable runnable = list.get();
		assertTrue("The list must run its members separately, but compiled to " +
				runnable.getClass().getSimpleName(), runnable instanceof OperationListRunner);
		return (OperationListRunner) runnable;
	}

	/**
	 * Asserts that every element of {@code dst} is {@code 2 * src + 1}.
	 *
	 * @param src the input buffer
	 * @param dst the output buffer
	 */
	private void assertChained(PackedCollection src, PackedCollection dst) {
		for (int i = 0; i < SIZE; i++) {
			assertEquals(2.0 * src.toDouble(i) + 1.0, dst.toDouble(i));
		}
	}
}
