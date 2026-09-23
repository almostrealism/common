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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A {@link ContextSpecific} value belongs to the data context it was created under.
 * One created inside a scoped context must not be handed out after that context is
 * destroyed, even when the holder was never registered as a context listener.
 */
public class ContextSpecificScopeTest extends TestSuiteBase {
	/** A value first created inside a scoped context is replaced once that context ends. */
	@Test(timeout = 60_000)
	public void valueFromDestroyedScopeIsReplaced() {
		List<Object> disposed = new ArrayList<>();
		ContextSpecific<Object> specific = new DefaultContextSpecific<>(Object::new, disposed::add);

		Object inside = dc(specific::getValue);
		Object again = dc(specific::getValue);
		Object outside = specific.getValue();

		assertNotSame("Second scope reuses the first scope's value", inside, again);
		assertNotSame("Value from a destroyed scope survives it", again, outside);
		assertEquals("Disposals", 2, disposed.size());
		assertTrue("First scope's value was disposed", disposed.contains(inside));
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

	/** A nested scope gets its own value, and the outer value is back once the scope ends. */
	@Test(timeout = 60_000)
	public void outerValueSurvivesScope() {
		ContextSpecific<Object> specific = new DefaultContextSpecific<>(Object::new);

		Object before = specific.getValue();
		Object inside = dc(specific::getValue);
		Object after = specific.getValue();

		assertNotSame("Scoped value distinct from the outer value", before, inside);
		assertSame("Outer value after the scope", before, after);
	}
}
