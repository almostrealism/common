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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * An evaluable or operation compiles its kernel under whatever compute context is
 * current the first time it runs. That placement is deliberate, made from the caller's
 * requirements and the computer's choice for the computation, so once the context is
 * destroyed the kernel is unusable and using it is an error. Nothing may quietly
 * compile it again somewhere else.
 *
 * <p>Before this was enforced, a stale kernel reallocated its arguments into the
 * destroyed provider, which failed on a dispatch thread and left the evaluating thread
 * waiting on a readiness latch that never fired.</p>
 */
public class ScopedContextKernelReuseTest extends TestSuiteBase {
	/** An evaluable first run inside a scoped context fails when used after the scope ends. */
	@Test(timeout = 120_000)
	public void evaluableFailsAfterScopedContextDestroyed() {
		int length = 1024;
		CollectionProducer doubled = c(new PassThroughProducer<>(shape(length), 0)).multiply(2.0);
		Evaluable<PackedCollection> ev = doubled.get();

		dc(() -> ev.evaluate(new PackedCollection(shape(length)).randFill()));

		assertDeadContext(() -> ev.evaluate(new PackedCollection(shape(length)).randFill()));
	}

	/**
	 * The compiled evaluable itself, not the holder around it, fails after the scope:
	 * its output metadata is read before anything is dispatched, and the dead context
	 * must be noticed before that read.
	 */
	@Test(timeout = 120_000)
	public void compiledEvaluableFailsAfterScopedContextDestroyed() {
		int length = 512;
		CollectionProducer doubled = c(new PassThroughProducer<>(shape(length), 0)).multiply(2.0);
		HardwareEvaluable<PackedCollection> ev = (HardwareEvaluable<PackedCollection>) doubled.get();

		Evaluable<PackedCollection> compiled = dc(() -> {
			Evaluable<PackedCollection> inner = ev.getKernel().getValue();
			inner.evaluate(new PackedCollection(shape(length)).randFill());
			return inner;
		});

		assertDeadContext(() -> compiled.evaluate(new PackedCollection(shape(length)).randFill()));
	}

	/**
	 * An operation that holds its compiled instructions directly fails after the scope,
	 * before its stale argument bindings can be consulted.
	 */
	@Test(timeout = 120_000)
	public void operationFailsAfterScopedContextDestroyed() {
		int length = 512;
		PackedCollection input = new PackedCollection(shape(length)).randFill();
		PackedCollection output = new PackedCollection(shape(length));

		Runnable op = dc(() -> {
			Runnable inner = a(cp(output), cp(input).multiply(2.0)).get();
			inner.run();
			return inner;
		});

		assertDeadContext(op);
	}

	/** Runs the action and requires the dead-context failure, naming the actual outcome otherwise. */
	private void assertDeadContext(Runnable action) {
		try {
			action.run();
		} catch (IllegalStateException e) {
			log("Rejected as expected: " + e.getMessage());
			return;
		}

		throw new AssertionError("Use after the scope should fail with IllegalStateException");
	}
}
