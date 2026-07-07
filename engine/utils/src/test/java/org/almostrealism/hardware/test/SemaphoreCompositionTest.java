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

import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validates {@link Semaphore#all(List)}, the merge
 * primitive for an operation that depends on several prior completions.
 */
public class SemaphoreCompositionTest extends TestSuiteBase {

	/**
	 * Merging nothing (or only nulls, representing fully synchronous work) yields null,
	 * and merging a single semaphore returns that semaphore directly.
	 */
	@Test(timeout = 30000)
	public void degenerateForms() {
		assertTrue(Semaphore.all(null) == null);
		assertTrue(Semaphore.all(Arrays.asList(null, null)) == null);

		DefaultLatchSemaphore only = new DefaultLatchSemaphore((Semaphore) null, 1);
		assertTrue(Semaphore.all(Arrays.asList(null, only, null)) == only);
	}

	/**
	 * A composite over several pending semaphores completes only once every member has
	 * completed, regardless of completion order.
	 */
	@Test(timeout = 30000)
	public void completesAfterAllMembers() throws InterruptedException {
		DefaultLatchSemaphore a = new DefaultLatchSemaphore((Semaphore) null, 1);
		DefaultLatchSemaphore b = new DefaultLatchSemaphore((Semaphore) null, 1);
		DefaultLatchSemaphore c = new DefaultLatchSemaphore((Semaphore) null, 1);

		Semaphore combined = Semaphore.all(Arrays.asList(a, null, b, c));
		assertTrue(combined != a && combined != b && combined != c);

		AtomicBoolean released = new AtomicBoolean(false);
		Thread waiter = new Thread(() -> {
			combined.waitFor();
			released.set(true);
		}, "SemaphoreCompositionTest waiter");
		waiter.start();

		b.countDown();
		c.countDown();

		// The composite must still be held open by the remaining member.
		waiter.join(250);
		assertTrue(!released.get());

		a.countDown();
		waiter.join(10000);
		assertTrue(released.get());
	}
}
