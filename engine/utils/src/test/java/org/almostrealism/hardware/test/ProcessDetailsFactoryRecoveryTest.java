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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.ProcessDetailsFactory;
import org.almostrealism.hardware.arguments.ProcessArgumentEvaluator;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies that a failure during argument preparation does not permanently
 * poison the {@link ProcessDetailsFactory} of a compiled operation.
 *
 * <p>Argument preparation can fail for reasons that are specific to a single
 * invocation — most notably a backend kernel compilation error raised while
 * obtaining an argument's {@link Evaluable} (for example, Metal rejecting a
 * kernel with more than 31 buffer arguments). Compiled operations are shared,
 * so the factory must remain usable after such a failure: the next invocation
 * has to rebuild the argument state from scratch rather than reuse a snapshot
 * that was only partially populated when the failure occurred. A factory that
 * retains partially populated state produces a {@link NullPointerException}
 * from an argument slot with neither a resolved {@link
 * org.almostrealism.hardware.MemoryData} nor an {@link Evaluable} on every
 * subsequent invocation.</p>
 */
public class ProcessDetailsFactoryRecoveryTest extends TestSuiteBase implements TestFeatures {
	/**
	 * Evaluates a compiled operation, then makes its argument evaluator fail
	 * exactly once and confirms the operation recovers on the following
	 * invocation instead of failing with a {@link NullPointerException} from
	 * partially prepared argument state.
	 */
	@Test(timeout = 120000)
	public void recoversAfterFailedArgumentPreparation() {
		PackedCollection a = new PackedCollection(shape(8));
		PackedCollection b = new PackedCollection(shape(8));
		a.fill(pos -> 2.0);
		b.fill(pos -> 3.0);

		CollectionProducer sum = add(traverseEach(p(a)), traverseEach(p(b)));
		Evaluable<PackedCollection> ev = sum.get();

		// Prime the operation so it is compiled and its details factory exists
		PackedCollection primed = ev.evaluate();
		for (int i = 0; i < 8; i++) {
			assertEquals(5.0, primed.toDouble(i));
		}

		AcceleratedComputationEvaluable<?> kernel = kernel(ev);
		ProcessDetailsFactory<?> factory = kernel.getDetailsFactory();
		ProcessArgumentEvaluator original = factory.getEvaluator();

		AtomicBoolean failPreparation = new AtomicBoolean(true);

		try {
			factory.setEvaluator(new ProcessArgumentEvaluator() {
				@Override
				public <V> Evaluable<? extends Multiple<V>> getEvaluable(ArrayVariable<V> argument) {
					if (failPreparation.get()) {
						throw new HardwareException("Simulated argument preparation failure");
					}

					return original.getEvaluable(argument);
				}
			});

			// Force the next invocation to prepare arguments again,
			// encountering the simulated failure partway through
			factory.reset();

			try {
				ev.evaluate();
				Assert.fail("Argument preparation was expected to fail");
			} catch (HardwareException e) {
				log("Argument preparation failed as intended: " + e.getMessage());
			}

			// The failure is gone; the operation must recover rather than
			// reuse argument state left behind by the failed preparation
			failPreparation.set(false);

			PackedCollection recovered = ev.evaluate();
			for (int i = 0; i < 8; i++) {
				assertEquals(5.0, recovered.toDouble(i));
			}
		} finally {
			factory.setEvaluator(original);
		}
	}

	/**
	 * Unwraps any {@link HardwareEvaluable} layers to reach the compiled
	 * {@link AcceleratedComputationEvaluable} whose details factory manages
	 * argument preparation.
	 *
	 * @param ev The evaluable produced for a compiled operation
	 * @return The underlying compiled evaluable
	 */
	private AcceleratedComputationEvaluable<?> kernel(Evaluable<?> ev) {
		Evaluable<?> inner = ev;

		while (inner instanceof HardwareEvaluable) {
			inner = ((HardwareEvaluable<?>) inner).getKernel().getValue();
		}

		return (AcceleratedComputationEvaluable<?>) inner;
	}
}
