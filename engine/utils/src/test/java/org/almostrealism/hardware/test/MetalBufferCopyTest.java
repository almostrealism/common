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

import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.metal.MTLBuffer;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalMemory;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Verifies the copies {@link MetalComputeContext#copy} queues on the command buffer, which are
 * dispatches of a copy kernel rather than blits: every copied element lands at its destination
 * offset, nothing outside the destination region changes, and each unit width the kernel copies
 * with (four, two or one bytes, chosen by alignment) reproduces the source bytes exactly.
 */
public class MetalBufferCopyTest extends TestSuiteBase {
	/** Number of 32-bit words in each buffer of {@link #copiesBytesAtEveryUnitWidth()}. */
	private static final int WORDS = 1100;

	/**
	 * Copies regions of collections between element offsets through the context's copy, for
	 * lengths below, at and beyond the width of one threadgroup, and checks that the destination
	 * holds the source region at its offset and is unchanged everywhere else.
	 */
	@Test(timeout = 60000)
	public void copiesRegionsBetweenOffsets() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		Assume.assumeTrue("Requires a Metal compute context", metal != null);

		// {length, source offset, destination offset} in elements
		int[][] cases = {{1, 0, 0}, {7, 3, 11}, {1024, 5, 0}, {1025, 0, 9}, {4099, 13, 2}};

		for (int[] c : cases) {
			int length = c[0];
			int sourceOffset = c[1];
			int destinationOffset = c[2];

			PackedCollection source = new PackedCollection(sourceOffset + length + 3);
			PackedCollection destination = new PackedCollection(destinationOffset + length + 3);
			rand(source.getShape()).add(1.0).into(source.traverseEach()).evaluate();

			assertTrue("The source must be device memory", source.getMem() instanceof MetalMemory);
			assertTrue("The destination must be device memory", destination.getMem() instanceof MetalMemory);

			Semaphore done = metal.copy(source.range(shape(length), sourceOffset),
					destination.range(shape(length), destinationOffset), null);
			done.waitFor();

			double[] from = source.toArray();
			double[] to = destination.toArray();

			for (int i = 0; i < to.length; i++) {
				int j = i - destinationOffset;
				double expected = j >= 0 && j < length ? from[sourceOffset + j] : 0.0;
				assertEquals(expected, to[i]);
			}

			source.destroy();
			destination.destroy();
		}
	}

	/**
	 * Copies between byte offsets with {@link MetalComputeContext#copyCommand} at each unit width
	 * the copy kernel uses — four, two and one bytes — including a copy wider than one threadgroup
	 * and a copy of nothing, and checks the whole destination buffer word for word against the
	 * same copy performed on the host.
	 */
	@Test(timeout = 60000)
	public void copiesBytesAtEveryUnitWidth() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		Assume.assumeTrue("Requires a Metal compute context", metal != null);

		// {size, source offset, destination offset} in bytes
		int[][] cases = {{24, 8, 4}, {10, 2, 6}, {7, 3, 9}, {4099, 1, 5}, {0, 4, 8}};
		Random random = new Random(0xC0B1);

		for (int[] c : cases) {
			int[] sourceWords = random.ints(WORDS).toArray();
			int[] destinationWords = random.ints(WORDS).toArray();
			MTLBuffer source = metal.getMtlDevice().newIntBuffer32(sourceWords);
			MTLBuffer destination = metal.getMtlDevice().newIntBuffer32(destinationWords);

			try {
				metal.getCommandRunner().submit(null,
						metal.copyCommand(source, c[1], destination, c[2], c[0]), null, null).waitFor();

				ByteBuffer from = bytes(sourceWords);
				ByteBuffer expected = bytes(destinationWords);
				for (int i = 0; i < c[0]; i++) {
					expected.put(c[2] + i, from.get(c[1] + i));
				}

				ByteBuffer actual = ByteBuffer.allocateDirect(WORDS * 4).order(ByteOrder.nativeOrder());
				destination.getContents(actual.asFloatBuffer(), 0, WORDS);

				for (int i = 0; i < WORDS; i++) {
					assertEquals(expected.getInt(4 * i), actual.getInt(4 * i));
				}
			} finally {
				source.release();
				destination.release();
			}
		}
	}

	/**
	 * Returns the bytes of the given 32-bit words in native order.
	 *
	 * @param words the words
	 * @return a buffer holding their bytes
	 */
	private static ByteBuffer bytes(int[] words) {
		ByteBuffer buffer = ByteBuffer.allocate(words.length * 4).order(ByteOrder.nativeOrder());
		buffer.asIntBuffer().put(words);
		return buffer;
	}
}
