/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.Computation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Periodic;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Supplier;

/**
 * Tests for the {@link Periodic} computation, verifying that counter-based
 * periodic execution works correctly in both compiled and Java fallback paths.
 *
 * @see Periodic
 */
public class PeriodicTest extends TestSuiteBase {

	/**
	 * Verifies that a compilable Periodic executes its body exactly
	 * the expected number of times when invoked repeatedly.
	 */
	@Test(timeout = 30000)
	public void testPeriodicCounting() {
		int period = 5;
		int totalTicks = 15;

		PackedCollection target = new PackedCollection(1);
		target.setMem(0, 0.0);

		Computation atom = a(1, p(target), cp(target).add(c(1.0)));
		Periodic periodic = new Periodic(atom, period);

		Runnable compiled = periodic.get();
		for (int i = 0; i < totalTicks; i++) {
			compiled.run();
		}

		assertEquals(3.0, target.toDouble(0), 0.001);
	}

	/**
	 * Verifies that a Periodic wrapped inside a Loop executes the
	 * body the correct number of times.
	 */
	@Test(timeout = 30000)
	public void testPeriodicInsideLoop() {
		int period = 5;
		int totalTicks = 15;

		PackedCollection target = new PackedCollection(1);
		target.setMem(0, 0.0);

		Computation atom = a(1, p(target), cp(target).add(c(1.0)));
		Supplier<Runnable> loopedPeriodic = lp(new Periodic(atom, period), totalTicks);

		loopedPeriodic.get().run();

		assertEquals(3.0, target.toDouble(0), 0.001);
	}

	/**
	 * Verifies that the Java fallback path (non-Computation atom via
	 * process optimization) correctly implements periodic counting.
	 */
	@Test(timeout = 30000)
	public void testPeriodicJavaFallback() {
		int period = 4;
		int totalTicks = 12;

		PackedCollection target = new PackedCollection(1);
		target.setMem(0, 0.0);

		PackedCollection counter = new PackedCollection(1);
		counter.setMem(0, 0.0);

		OperationList body = new OperationList("Periodic Fallback Body");
		body.add(() -> () -> target.setMem(0, target.toDouble(0) + 1.0));

		Supplier<Runnable> periodic = periodic(body, period);
		Runnable compiled = periodic.get();

		for (int i = 0; i < totalTicks; i++) {
			compiled.run();
		}

		assertEquals(3.0, target.toDouble(0), 0.001);
	}

	/**
	 * Verifies that the counter resets to zero after each body execution,
	 * so the next cycle starts fresh.
	 */
	@Test(timeout = 30000)
	public void testPeriodicCounterReset() {
		int period = 3;

		PackedCollection target = new PackedCollection(1);
		target.setMem(0, 0.0);

		Computation atom = a(1, p(target), cp(target).add(c(1.0)));
		Periodic periodic = new Periodic(atom, period);

		Runnable compiled = periodic.get();

		// First cycle: 3 ticks -> body executes once
		for (int i = 0; i < 3; i++) {
			compiled.run();
		}
		assertEquals(1.0, target.toDouble(0), 0.001);

		// Second cycle: 3 more ticks -> body executes again
		for (int i = 0; i < 3; i++) {
			compiled.run();
		}
		assertEquals(2.0, target.toDouble(0), 0.001);
	}

	/**
	 * Verifies that the counter persists across multiple invocations of
	 * the compiled runnable, so partial cycles are not lost.
	 */
	@Test(timeout = 30000)
	public void testPeriodicCounterPersistence() {
		int period = 5;

		PackedCollection target = new PackedCollection(1);
		target.setMem(0, 0.0);

		Computation atom = a(1, p(target), cp(target).add(c(1.0)));
		Periodic periodic = new Periodic(atom, period);

		Runnable compiled = periodic.get();

		// 3 ticks - not enough for one cycle
		for (int i = 0; i < 3; i++) {
			compiled.run();
		}
		assertEquals(0.0, target.toDouble(0), 0.001);

		// 2 more ticks - completes first cycle (total 5)
		for (int i = 0; i < 2; i++) {
			compiled.run();
		}
		assertEquals(1.0, target.toDouble(0), 0.001);

		// 7 more ticks - completes one more cycle (total 12, 2 full cycles + 2 extra)
		for (int i = 0; i < 7; i++) {
			compiled.run();
		}
		assertEquals(2.0, target.toDouble(0), 0.001);
	}
}
