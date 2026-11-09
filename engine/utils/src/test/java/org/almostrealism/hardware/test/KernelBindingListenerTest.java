/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware.test;

import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.InstructionCacheCollisionException;
import org.almostrealism.hardware.KernelBindingListener;
import org.almostrealism.hardware.arguments.ProcessArgumentMap;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Coverage for the observation hooks the instruction-cache benchmark relies on: the
 * {@link KernelBindingListener} reports a compilation for the first of two structurally identical
 * computations and a reuse for the second, a dispatch for each evaluation and the aggregate copies
 * around it, and a collision is thrown as an {@link InstructionCacheCollisionException}.
 */
public class KernelBindingListenerTest extends TestSuiteBase {

	/** Records every notification as a line of text. */
	private static class Recorder implements KernelBindingListener {
		/** The notifications, in order. */
		private final List<String> events = new ArrayList<>();

		@Override
		public synchronized void compiled(OperationMetadata metadata, String signature,
										  int kernelArguments, int foldedArguments, int aggregateLength) {
			events.add("compiled " + (signature != null) + " " + kernelArguments + " " +
					foldedArguments + " " + aggregateLength);
		}

		@Override
		public synchronized void reused(OperationMetadata metadata, String signature, int kernelArguments) {
			events.add("reused " + kernelArguments);
		}

		@Override
		public synchronized void dispatched(OperationMetadata metadata, boolean aggregating) {
			events.add("dispatched " + aggregating);
		}

		@Override
		public synchronized void aggregateCopied(boolean copyBack, int length) {
			events.add((copyBack ? "copyBack " : "copyIn ") + length);
		}

		/** Returns the notifications so far and forgets them. */
		synchronized List<String> drain() {
			List<String> result = new ArrayList<>(events);
			events.clear();
			return result;
		}
	}

	/** Builds {@code x * (y + 1)} over fresh small inputs, which fold into an aggregate. */
	private CollectionProducer computation(int length) {
		PackedCollection x = new PackedCollection(length).fill(2.0);
		PackedCollection y = new PackedCollection(length).fill(3.0);
		return cp(x).multiply(cp(y).add(1.0));
	}

	/**
	 * The first computation compiles a kernel with two folded inputs, the second structurally
	 * identical one reuses it, and each evaluation is one aggregating dispatch preceded by a
	 * copy into the aggregate for every folded input.
	 */
	@Test(timeout = 120000)
	public void reportsCompilationReuseDispatchAndCopies() {
		Recorder recorder = new Recorder();
		KernelBindingListener previous = AcceleratedOperation.bindingListener;
		AcceleratedOperation.bindingListener = recorder;

		try {
			PackedCollection first = Process.optimized(computation(8)).get().evaluate();
			List<String> compiled = recorder.drain();
			log("firstEvents=" + compiled);

			PackedCollection second = Process.optimized(computation(8)).get().evaluate();
			List<String> reused = recorder.drain();
			log("secondEvents=" + reused);

			assertEquals(8.0, first.toDouble(0));
			assertEquals(8.0, second.toDouble(7));

			Assert.assertTrue(compiled.toString(), compiled.contains("compiled true 2 2 16"));
			Assert.assertTrue(compiled.toString(), compiled.contains("dispatched true"));
			Assert.assertEquals(compiled.toString(), 2, compiled.stream().filter("copyIn 8"::equals).count());

			Assert.assertTrue(reused.toString(), reused.contains("reused 2"));
			Assert.assertTrue(reused.toString(), reused.stream().noneMatch(e -> e.startsWith("compiled")));
			Assert.assertTrue(reused.toString(), reused.contains("dispatched true"));
			Assert.assertEquals(reused.toString(), 2, reused.stream().filter("copyIn 8"::equals).count());
		} finally {
			AcceleratedOperation.bindingListener = previous;
		}
	}

	/** With no listener installed, compiling and evaluating behaves exactly as before. */
	@Test(timeout = 120000)
	public void noListenerInstalled() {
		KernelBindingListener previous = AcceleratedOperation.bindingListener;
		AcceleratedOperation.bindingListener = null;

		try {
			PackedCollection result = Process.optimized(computation(4)).get().evaluate();
			assertEquals(8.0, result.toDouble(3));
		} finally {
			AcceleratedOperation.bindingListener = previous;
		}
	}

	/**
	 * A positioned argument with no substitution is a structural collision, and it is thrown
	 * as an {@link InstructionCacheCollisionException} of kind
	 * {@link InstructionCacheCollisionException.Kind#STRUCTURE}.
	 */
	@Test(timeout = 60000)
	public void missingSubstitutionIsStructuralCollision() {
		CollectionProducer sum = c(1.0).add(c(2.0));
		Process<?, ?> process = (Process<?, ?>) sum;

		Process<?, ?> child = process.getChildren().stream()
				.filter(candidate -> candidate instanceof Producer).findFirst().orElse(null);
		Assert.assertNotNull("The computation must have a Producer child to position", child);

		ArrayVariable<?> argument = CollectionVariable.create("arg0", (Supplier) child);
		ProcessArgumentMap map = new ProcessArgumentMap(process, List.of(argument));

		try {
			map.verifySubstitutions("structuralCollisionProbe");
			Assert.fail("A positioned argument with no substitution must fail verification");
		} catch (InstructionCacheCollisionException e) {
			Assert.assertEquals(InstructionCacheCollisionException.Kind.STRUCTURE, e.getKind());
			Assert.assertTrue(e.getMessage(), e.getMessage().startsWith("Instruction cache collision"));
		}
	}

	/**
	 * {@link InstructionCacheCollisionException#findIn(Throwable)} returns the collision itself, the
	 * innermost collision however deeply it is wrapped, and null for a chain without one.
	 */
	@Test(timeout = 60000)
	public void findInLocatesCollisionInCauseChain() {
		InstructionCacheCollisionException collision = new InstructionCacheCollisionException(
				InstructionCacheCollisionException.Kind.AGGREGATE_LAYOUT, "Instruction cache collision probe");

		Assert.assertSame(collision, InstructionCacheCollisionException.findIn(collision));
		Assert.assertSame(collision, InstructionCacheCollisionException.findIn(
				new RuntimeException("outer", new IllegalStateException("middle", collision))));
		Assert.assertEquals(InstructionCacheCollisionException.Kind.AGGREGATE_LAYOUT,
				InstructionCacheCollisionException.findIn(new RuntimeException(collision)).getKind());

		Assert.assertNull(InstructionCacheCollisionException.findIn(
				new RuntimeException("outer", new IllegalStateException("no collision"))));
		Assert.assertNull(InstructionCacheCollisionException.findIn(new RuntimeException()));
		Assert.assertNull(InstructionCacheCollisionException.findIn(null));
	}
}
