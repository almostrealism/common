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

package org.almostrealism.audio.line.test;

import org.almostrealism.audio.line.MockOutputLine;
import org.almostrealism.audio.line.OutputLineGroup;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link OutputLineGroup} synchronization and forwarding behavior.
 */
public class OutputLineGroupTest extends TestSuiteBase {

	/**
	 * Tests that getReadPosition() returns the minimum position across all members.
	 */
	@Test(timeout = 60000)
	public void readPositionReturnsMinimum() {
		SlowMockOutputLine fast = new SlowMockOutputLine(1024, 700);
		SlowMockOutputLine slow = new SlowMockOutputLine(1024, 300);

		OutputLineGroup group = new OutputLineGroup();
		group.addMember(fast);
		group.addMember(slow);

		Assert.assertEquals("Minimum read position should be used",
				300, group.getReadPosition());
	}

	/**
	 * Tests that an empty group returns 0 for getReadPosition().
	 */
	@Test(timeout = 60000)
	public void readPositionEmptyGroupReturnsZero() {
		OutputLineGroup group = new OutputLineGroup();
		Assert.assertEquals(0, group.getReadPosition());
	}

	/**
	 * Tests that getReadPosition() returns correct position for a single-member group.
	 */
	@Test(timeout = 60000)
	public void readPositionSingleMember() {
		SlowMockOutputLine line = new SlowMockOutputLine(1024, 300);
		OutputLineGroup group = new OutputLineGroup();
		group.addMember(line);

		Assert.assertEquals(300, group.getReadPosition());
	}

	/**
	 * Tests that write() forwards samples to all members.
	 */
	@Test(timeout = 60000)
	public void writeForwardsToAllMembers() {
		MockOutputLine a = new MockOutputLine(1024);
		MockOutputLine b = new MockOutputLine(1024);
		MockOutputLine c = new MockOutputLine(1024);

		OutputLineGroup group = new OutputLineGroup();
		group.addMember(a);
		group.addMember(b);
		group.addMember(c);

		PackedCollection sample = new PackedCollection(512);
		group.write(sample);

		Assert.assertEquals(512, a.getFramesWritten());
		Assert.assertEquals(512, b.getFramesWritten());
		Assert.assertEquals(512, c.getFramesWritten());
	}

	/**
	 * Tests that getBufferSize() returns the minimum buffer size across all members.
	 */
	@Test(timeout = 60000)
	public void bufferSizeReturnsMinimum() {
		MockOutputLine small = new MockOutputLine(512);
		MockOutputLine large = new MockOutputLine(2048);

		OutputLineGroup group = new OutputLineGroup();
		group.addMember(small);
		group.addMember(large);

		Assert.assertEquals(512, group.getBufferSize());
	}

	/**
	 * Tests that removeMember() correctly removes a member and stops forwarding to it.
	 */
	@Test(timeout = 60000)
	public void removeMember() {
		MockOutputLine a = new MockOutputLine(1024);
		MockOutputLine b = new MockOutputLine(1024);

		OutputLineGroup group = new OutputLineGroup();
		group.addMember(a);
		group.addMember(b);

		Assert.assertEquals(2, group.size());

		boolean removed = group.removeMember(a);
		Assert.assertTrue(removed);
		Assert.assertEquals(1, group.size());

		PackedCollection sample = new PackedCollection(256);
		group.write(sample);

		Assert.assertEquals(0, a.getFramesWritten());
		Assert.assertEquals(256, b.getFramesWritten());
	}

	/**
	 * Tests that start() and stop() delegate to all members.
	 */
	@Test(timeout = 60000)
	public void startStopDelegatesToAllMembers() {
		MockOutputLine a = new MockOutputLine(1024);
		MockOutputLine b = new MockOutputLine(1024);

		OutputLineGroup group = new OutputLineGroup();
		group.addMember(a);
		group.addMember(b);

		Assert.assertFalse(a.isActive());
		Assert.assertFalse(b.isActive());

		group.start();
		Assert.assertTrue(a.isActive());
		Assert.assertTrue(b.isActive());

		group.stop();
		Assert.assertFalse(a.isActive());
		Assert.assertFalse(b.isActive());
	}

	/**
	 * Tests that destroy() clears all members.
	 */
	@Test(timeout = 60000)
	public void destroyClearsMembers() {
		MockOutputLine a = new MockOutputLine(1024);
		MockOutputLine b = new MockOutputLine(1024);

		OutputLineGroup group = new OutputLineGroup();
		group.addMember(a);
		group.addMember(b);

		group.destroy();
		Assert.assertEquals(0, group.size());
	}

	/**
	 * A mock output line with a fixed read position for testing
	 * synchronization without timing dependencies.
	 */
	private static class SlowMockOutputLine extends MockOutputLine {
		/** Fixed read position returned by this mock. */
		private final int fixedReadPosition;

		/**
		 * Creates a SlowMockOutputLine with the specified buffer size and fixed read position.
		 *
		 * @param bufferSize the buffer size
		 * @param fixedReadPosition the fixed read position to return
		 */
		SlowMockOutputLine(int bufferSize, int fixedReadPosition) {
			super(bufferSize);
			this.fixedReadPosition = fixedReadPosition;
		}

		@Override
		public int getReadPosition() {
			return fixedReadPosition;
		}
	}
}
