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

import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link BufferDefaults}.
 */
public class BufferDefaultsTest extends TestSuiteBase {

	/**
	 * {@link BufferDefaults#padReadPosition(int, int)} must return a value that is
	 * always a valid index into the circular buffer, i.e. within {@code [0, bufferSize)}.
	 * When the padded read position lands exactly on the buffer boundary, it must wrap
	 * to {@code 0} rather than being left at {@code bufferSize}, which is one past the
	 * last valid frame.
	 */
	@Test(timeout = 30000)
	public void padReadPositionWrapsAtExactBufferBoundary() {
		int bufferSize = 65536;
		int padding = BufferDefaults.readGroupSensitivityPadding;
		int readPosition = bufferSize - padding;

		int padded = BufferDefaults.padReadPosition(readPosition, bufferSize);

		assertTrue("padReadPosition(" + readPosition + ", " + bufferSize + ") returned " +
						padded + ", which is not a valid index into a buffer of size " + bufferSize,
				padded < bufferSize);
		assertEquals(0, padded);
	}

	/**
	 * When the padded read position wraps exactly to the start of the buffer, the group
	 * it falls into is group {@code 0}. {@link BufferDefaults#isSafeGroup} must recognise
	 * that group as sensitive, so writing into group {@code 0} at that moment must be
	 * reported as unsafe.
	 */
	@Test(timeout = 30000)
	public void isSafeGroupDetectsWrapAroundBoundary() {
		int groupSize = BufferDefaults.batchSize * BufferDefaults.batchesPerGroup;
		int bufferSize = groupSize * BufferDefaults.groups;
		int padding = BufferDefaults.readGroupSensitivityPadding;
		int readPosition = bufferSize - padding;

		boolean safe = BufferDefaults.isSafeGroup(0, readPosition, groupSize, bufferSize);

		assertFalse("Writing to group 0 should be unsafe when the padded read position " +
				"wraps around to the start of the buffer", safe);
	}
}
