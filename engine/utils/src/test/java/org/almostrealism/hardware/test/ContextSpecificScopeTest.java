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

import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;
import org.almostrealism.hardware.ctx.ThreadLocalContextSpecific;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A {@link ContextSpecific} value belongs to the compute context it was created under.
 * One created inside a scoped context cannot be used after that context is destroyed,
 * and asking for it is an error rather than an occasion to build another; a holder
 * registered as a context listener is given a fresh value per context instead.
 */
public class ContextSpecificScopeTest extends TestSuiteBase {
	/** An unregistered holder's value from a destroyed scope is refused, not replaced. */
	@Test(timeout = 60_000)
	public void valueFromDestroyedScopeIsRefused() {
		ContextSpecific<Object> specific = new DefaultContextSpecific<>(Object::new);
		Object inside = dc(specific::getValue);
		assertTrue("Value was created inside the scope", inside != null);

		try {
			specific.getValue();
		} catch (IllegalStateException e) {
			log("Refused as expected: " + e.getMessage());
			return;
		}

		throw new AssertionError("A value from a destroyed context must not be handed out");
	}

	/**
	 * A holder registered for lifecycle callbacks has the value it created under a
	 * scope disposed of when that scope ends, and keeps the outer value untouched.
	 */
	@Test(timeout = 60_000)
	public void registeredValueIsDisposedWithItsScope() {
		List<Object> disposed = new ArrayList<>();
		ContextSpecific<Object> specific = new DefaultContextSpecific<>(Object::new, disposed::add);
		specific.init();

		try {
			Object before = specific.getValue();
			Object inside = dc(specific::getValue);
			Object after = specific.getValue();

			assertNotSame("Scoped value distinct from the outer value", before, inside);
			assertSame("Outer value after the scope", before, after);
			assertEquals("Disposals", 1, disposed.size());
			assertTrue("Scoped value was disposed", disposed.contains(inside));
		} finally {
			specific.destroy();
		}
	}

	/**
	 * A thread-local holder disposes of the values every thread created, even when
	 * the thread doing the disposing never created a value of its own.
	 */
	@Test(timeout = 60_000)
	public void threadLocalValuesAreDisposedFromAnyThread() throws InterruptedException {
		List<Object> disposed = Collections.synchronizedList(new ArrayList<>());
		ContextSpecific<Object> specific = new ThreadLocalContextSpecific<>(Object::new, disposed::add);
		Object[] fromWorker = new Object[1];

		Thread worker = new Thread(() -> fromWorker[0] = specific.getValue());
		worker.start();
		worker.join();

		specific.destroy();

		assertTrue("Worker created a value", fromWorker[0] != null);
		assertTrue("Worker's value was disposed", disposed.contains(fromWorker[0]));
	}

	/**
	 * An unregistered holder keeps the value it made under the outer context through a
	 * nested scope and after it: that context stays alive throughout, so the value is
	 * still usable. Only a registered holder is given a value per context.
	 */
	@Test(timeout = 60_000)
	public void outerValueSurvivesScope() {
		ContextSpecific<Object> specific = new DefaultContextSpecific<>(Object::new);

		Object before = specific.getValue();
		Object inside = dc(specific::getValue);
		Object after = specific.getValue();

		assertSame("Outer value inside the scope", before, inside);
		assertSame("Outer value after the scope", before, after);
	}
}
