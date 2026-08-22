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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * A kernel must not be dispatched with memory that has already been released.
 *
 * <p>Arguments reach compiled code as bare addresses. Native memory does not
 * clear its pointer when the block behind it is freed, so a released argument
 * arrives as a number that no longer maps to anything, and the process is lost
 * with nothing to say which operation or which argument was at fault. Refusing
 * the dispatch turns that into an exception naming both.</p>
 */
public class ReleasedMemoryDispatchTest extends TestSuiteBase {

	/** Memory whose provider decides whether it counts as released. */
	private static class TestMemory implements Memory {
		/** The provider that decides whether this memory counts as released. */
		private final MemoryProvider provider;

		TestMemory(MemoryProvider provider) { this.provider = provider; }

		@Override
		public MemoryProvider getProvider() { return provider; }
	}

	/**
	 * A provider that reports whatever the test asks it to, standing in for one
	 * that tracks what it has handed out.
	 */
	private static class TestProvider implements MemoryProvider<TestMemory> {
		/** What this provider reports about memory it handed out. */
		private boolean released;

		TestProvider(boolean released) { this.released = released; }

		@Override
		public String getName() { return "test"; }

		@Override
		public int getNumberSize() { return 8; }

		@Override
		public void deallocate(int size, TestMemory mem) { }

		@Override
		public void getMem(TestMemory mem, int sOffset, double[] out, int oOffset, int length) { }

		@Override
		public boolean isReleased(Memory mem) { return released; }
	}

	/** Data over one {@link Memory}, or over none once destroyed. */
	private static class TestData implements MemoryData {
		/** The memory this data points at, or {@code null} once destroyed. */
		private Memory mem;

		TestData(Memory mem) { this.mem = mem; }

		@Override
		public Memory getMem() { return mem; }

		@Override
		public void reassign(Memory mem) { this.mem = mem; }

		@Override
		public int getOffset() { return 0; }

		@Override
		public int getMemLength() { return 1; }

		@Override
		public MemoryData getDelegate() { return null; }

		@Override
		public int getDelegateOffset() { return 0; }

		@Override
		public void setDelegate(MemoryData m, int offset, TraversalOrdering order) { }

		@Override
		public TraversalOrdering getDelegateOrdering() { return null; }

		@Override
		public void destroy() { this.mem = null; }
	}

	/** The smallest operator that still runs the real argument preparation. */
	private static class TestOperator extends HardwareOperator {
		/** The provider whose memory this operator accepts without migrating it. */
		private final MemoryProvider<? extends Memory> provider;

		TestOperator(MemoryProvider<? extends Memory> provider) { this.provider = provider; }

		@Override
		public boolean isGPU() { return false; }

		@Override
		public List<MemoryProvider<? extends Memory>> getSupportedMemory() {
			return List.of(provider);
		}

		@Override
		protected String getHardwareName() { return "test"; }

		@Override
		protected int getArgCount() { return 1; }

		@Override
		public String getName() { return "testOperation"; }

		@Override
		public OperationMetadata getMetadata() { return null; }

		@Override
		public Semaphore accept(Object[] args, Semaphore dependsOn) {
			prepare(args);
			return null;
		}

		/** Runs argument preparation the way a real dispatch would. */
		void prepare(Object[] args) {
			prepareArguments(getArgCount(), args);
		}
	}

	/** Memory that is present and not released is usable. */
	@Test(timeout = 30000)
	public void liveMemoryIsAvailable() {
		TestData data = new TestData(new TestMemory(new TestProvider(false)));

		Assert.assertTrue(data.isAvailable());
		Assert.assertFalse(data.isDestroyed());
	}

	/** Data that has been destroyed points at no memory at all. */
	@Test(timeout = 30000)
	public void destroyedDataIsNotAvailable() {
		TestData data = new TestData(new TestMemory(new TestProvider(false)));
		data.destroy();

		Assert.assertTrue(data.isDestroyed());
		Assert.assertFalse(data.isAvailable());
	}

	/**
	 * The case {@code isDestroyed} cannot see: the data still points at memory,
	 * but the block behind it has been freed. This is what the native types do
	 * — they go on returning the address of a block that is gone.
	 */
	@Test(timeout = 30000)
	public void releasedMemoryIsNotAvailableEvenThoughItIsStillPointedAt() {
		TestData data = new TestData(new TestMemory(new TestProvider(true)));

		Assert.assertFalse("The data still holds its memory, so it does not read"
				+ " as destroyed", data.isDestroyed());
		Assert.assertFalse("but the memory has been released, so it is not usable",
				data.isAvailable());
	}

	/** A provider that does not track what it hands out cannot object. */
	@Test(timeout = 30000)
	public void memoryFromAnUntrackedProviderIsAssumedAvailable() {
		Assert.assertFalse(new TestProvider(false).isReleased(null));
	}

	/** Preparing a released argument must fail rather than reach the kernel. */
	@Test(timeout = 30000)
	public void dispatchRefusesReleasedMemory() {
		TestProvider provider = new TestProvider(true);
		TestOperator operator = new TestOperator(provider);
		TestData data = new TestData(new TestMemory(provider));

		try {
			operator.prepare(new Object[] { data });
			Assert.fail("A released argument must not be prepared for dispatch");
		} catch (HardwareException e) {
			Assert.assertTrue("The failure must name the argument: " + e.getMessage(),
					e.getMessage().contains("argument 0"));
			Assert.assertTrue("The failure must name the operation: " + e.getMessage(),
					e.getMessage().contains("testOperation"));
		}
	}

	/** Preparing a destroyed argument must fail the same way. */
	@Test(timeout = 30000)
	public void dispatchRefusesDestroyedMemory() {
		TestProvider provider = new TestProvider(false);
		TestOperator operator = new TestOperator(provider);
		TestData data = new TestData(new TestMemory(provider));
		data.destroy();

		try {
			operator.prepare(new Object[] { data });
			Assert.fail("A destroyed argument must not be prepared for dispatch");
		} catch (HardwareException expected) {
			// the dispatch was refused, which is the point
		}
	}

	/** A live argument still prepares, so the check costs a valid dispatch nothing. */
	@Test(timeout = 30000)
	public void dispatchAcceptsLiveMemory() {
		TestProvider provider = new TestProvider(false);
		TestOperator operator = new TestOperator(provider);
		TestData data = new TestData(new TestMemory(provider));

		operator.prepare(new Object[] { data });
	}
}
