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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies that an {@link Evaluable} held across the eviction of its shared
 * instruction manager continues to produce correct results.
 *
 * <p>Holding a compiled {@link Evaluable} long-term is a recommended usage
 * pattern, but the compiled instructions it depends on live in a bounded
 * cache and can be evicted and destroyed at any time by unrelated activity.
 * The holder's argument bindings are derived from the destroyed manager's
 * compiled scope, so after eviction the holder must rebind against whatever
 * manager serves it next — executing with the old bindings silently reads
 * and writes the wrong memory. These tests exercise that lifecycle
 * deterministically using {@code DefaultComputer.evictInstructions}, which
 * removes and destroys a manager exactly as capacity pressure would.</p>
 */
public class InstructionEvictionRebindTest extends TestSuiteBase {

	/**
	 * Unwraps the compiled evaluable underlying the given wrapper.
	 *
	 * @param ev the evaluable produced for a compiled computation
	 * @return the underlying compiled evaluable
	 */
	private AcceleratedComputationEvaluable<?> kernel(Evaluable<?> ev) {
		Evaluable<?> inner = ev;

		while (inner instanceof HardwareEvaluable) {
			inner = ((HardwareEvaluable<?>) inner).getKernel().getValue();
		}

		return (AcceleratedComputationEvaluable<?>) inner;
	}

	/**
	 * A held {@link Evaluable} must keep producing correct results after its
	 * instruction manager is evicted and destroyed, including reflecting input
	 * data that changed after the eviction.
	 */
	@Test(timeout = 120000)
	public void heldEvaluableSurvivesEviction() {
		PackedCollection a = new PackedCollection(4);
		a.setMem(0, 1.0, 2.0, 3.0, 4.0);
		PackedCollection b = new PackedCollection(4);
		b.setMem(0, 10.0, 20.0, 30.0, 40.0);

		Evaluable<PackedCollection> held = cp(a).add(cp(b)).get();

		PackedCollection first = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), first.toDouble(i));
		}

		String signature = kernel(held).signature();
		assertNotNull("A provider-based computation should have a signature", signature);

		Hardware.getLocalHardware().getComputer().evictInstructions(signature);

		b.setMem(0, 100.0, 200.0, 300.0, 400.0);
		PackedCollection second = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), second.toDouble(i));
		}
	}

	/**
	 * Two holders of structurally identical computations across an eviction:
	 * after the shared manager is destroyed, the second holder's evaluation
	 * produces a replacement manager, and the first holder must rebind against
	 * it (or fail loudly) rather than execute the replacement kernel through
	 * bindings created against the destroyed manager.
	 */
	@Test(timeout = 120000)
	public void rebindsAfterReplacementManager() {
		PackedCollection a = new PackedCollection(4);
		a.setMem(0, 1.0, 2.0, 3.0, 4.0);
		PackedCollection b = new PackedCollection(4);
		b.setMem(0, 10.0, 20.0, 30.0, 40.0);
		PackedCollection c = new PackedCollection(4);
		c.setMem(0, 5.0, 6.0, 7.0, 8.0);
		PackedCollection d = new PackedCollection(4);
		d.setMem(0, 50.0, 60.0, 70.0, 80.0);

		Evaluable<PackedCollection> held = cp(a).add(cp(b)).get();
		Evaluable<PackedCollection> peer = cp(c).add(cp(d)).get();

		PackedCollection first = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), first.toDouble(i));
		}

		String signature = kernel(held).signature();
		assertNotNull("A provider-based computation should have a signature", signature);

		Hardware.getLocalHardware().getComputer().evictInstructions(signature);

		// The peer's evaluation compiles the replacement manager for this signature
		PackedCollection peerResult = peer.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(c.toDouble(i) + d.toDouble(i), peerResult.toDouble(i));
		}

		a.setMem(0, 9.0, 8.0, 7.0, 6.0);
		PackedCollection second = held.evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), second.toDouble(i));
		}
	}
}
