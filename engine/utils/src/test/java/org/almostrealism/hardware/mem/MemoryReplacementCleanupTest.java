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

package org.almostrealism.hardware.mem;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that {@link MemoryReplacementManager} frees every temporary it creates, on the
 * paths where cleanup can be interrupted: a temporary whose destroy fails, and a temporary
 * allocation that fails part way through {@link MemoryReplacementManager#processArguments}.
 *
 * <p>Each manager is given no target provider, so every argument is replaced, and each argument
 * is its own root, so each becomes a separate replacement group with its own temporary.</p>
 */
public class MemoryReplacementCleanupTest extends TestSuiteBase {

	/**
	 * Regression: a temporary whose destroy throws must not stop the remaining temporaries from
	 * being destroyed. The first failure is rethrown with any later one attached as suppressed.
	 */
	@Test(timeout = 30000)
	public void releaseTemporariesDestroysEveryTemporaryDespiteFailures() {
		List<MemoryData> created = new ArrayList<>();
		MemoryReplacementManager manager = new MemoryReplacementManager(null, null, (length, atomic) -> {
			MemoryData tmp = created.size() < 2 ? new FailingDestroy(length, atomic) : new Bytes(length, atomic);
			created.add(tmp);
			return tmp;
		});

		PackedCollection a = pack(1.0, 2.0);
		PackedCollection b = pack(3.0, 4.0);
		PackedCollection c = pack(5.0, 6.0);

		try {
			manager.processArguments(new Object[] { a, b, c });
			Assert.assertEquals(3, created.size());

			RuntimeException failure = null;

			try {
				manager.releaseTemporaries();
			} catch (RuntimeException e) {
				failure = e;
			}

			Assert.assertNotNull("A failed destroy must be reported", failure);
			Assert.assertEquals(FailingDestroy.MESSAGE, failure.getMessage());
			Assert.assertEquals(1, failure.getSuppressed().length);
			Assert.assertEquals(FailingDestroy.MESSAGE, failure.getSuppressed()[0].getMessage());

			for (MemoryData tmp : created) {
				Assert.assertTrue("Every temporary must be destroyed", tmp.isDestroyed());
			}

			manager.releaseTemporaries();
		} finally {
			a.destroy();
			b.destroy();
			c.destroy();
		}
	}

	/**
	 * Regression: when allocating a later temporary fails, the temporaries already created are
	 * freed and the copies registered against them are discarded before the failure propagates,
	 * rather than being stranded with no completion chain to release them.
	 */
	@Test(timeout = 30000)
	public void allocationFailureReleasesEarlierTemporaries() {
		List<MemoryData> created = new ArrayList<>();
		IllegalStateException limit = new IllegalStateException("Memory max reached");
		MemoryReplacementManager manager = new MemoryReplacementManager(null, null, (length, atomic) -> {
			if (created.size() == 2) throw limit;

			MemoryData tmp = new Bytes(length, atomic);
			created.add(tmp);
			return tmp;
		});

		PackedCollection a = pack(1.0, 2.0);
		PackedCollection b = pack(3.0, 4.0);
		PackedCollection c = pack(5.0, 6.0);

		try {
			IllegalStateException thrown = null;

			try {
				manager.processArguments(new Object[] { a, b, c });
			} catch (IllegalStateException e) {
				thrown = e;
			}

			Assert.assertSame("The allocation failure must propagate unchanged", limit, thrown);
			Assert.assertEquals(2, created.size());

			for (MemoryData tmp : created) {
				Assert.assertTrue("Temporaries created before the failure must be freed", tmp.isDestroyed());
			}

			Assert.assertTrue("No copy may remain registered against a freed temporary", manager.isEmpty());
			Assert.assertFalse("The arguments themselves are untouched", a.isDestroyed());
			Assert.assertArrayEquals(new double[] { 1.0, 2.0 }, a.toArray(0, 2), 0.0);
		} finally {
			a.destroy();
			b.destroy();
			c.destroy();
		}
	}

	/**
	 * Allocating every temporary successfully leaves them all alive until
	 * {@link MemoryReplacementManager#releaseTemporaries()} is called, which frees them all.
	 */
	@Test(timeout = 30000)
	public void successfulProcessingKeepsTemporariesUntilReleased() {
		List<MemoryData> created = new ArrayList<>();
		MemoryReplacementManager manager = new MemoryReplacementManager(null, null, (length, atomic) -> {
			MemoryData tmp = new Bytes(length, atomic);
			created.add(tmp);
			return tmp;
		});

		PackedCollection a = pack(1.0, 2.0);
		PackedCollection b = pack(3.0, 4.0);

		try {
			Object[] result = manager.processArguments(new Object[] { a, b, "not memory" });

			Assert.assertEquals(2, created.size());
			Assert.assertNotSame(a, result[0]);
			Assert.assertNotSame(b, result[1]);
			Assert.assertEquals("not memory", result[2]);
			Assert.assertEquals(2, manager.getPrepareOperations().size());
			Assert.assertEquals(2, manager.getPostprocessOperations().size());

			for (MemoryData tmp : created) {
				Assert.assertFalse(tmp.isDestroyed());
			}

			manager.releaseTemporaries();

			for (MemoryData tmp : created) {
				Assert.assertTrue(tmp.isDestroyed());
			}
		} finally {
			a.destroy();
			b.destroy();
		}
	}

	/** A temporary that frees its memory on destroy and then reports a deallocation failure. */
	private static final class FailingDestroy extends Bytes {
		/** The message of the failure every destroy reports. */
		static final String MESSAGE = "deallocation failed";

		/**
		 * Allocates the temporary.
		 *
		 * @param length the number of elements
		 * @param atomic the atomic length
		 */
		FailingDestroy(int length, int atomic) {
			super(length, atomic);
		}

		/** Frees the memory, then reports a failure as a failing provider deallocation would. */
		@Override
		public void destroy() {
			super.destroy();
			throw new IllegalStateException(MESSAGE);
		}
	}
}
