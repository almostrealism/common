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

package org.almostrealism.audio;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies {@link BatchedPatternRenderer#buildScatterAdd}, the offset-aware
 * scatter-add that places each note's row into the output window at its
 * destination offset. This is the batched generalization of the per-note ranged
 * accumulate in {@code PatternFeatures.sumToDestination}; correctness here is the
 * gate (spike #1) for the real-time a2 batching design.
 */
public class BatchedScatterAddTest extends TestSuiteBase implements TemporalFeatures {

	/** Builds a {@link BatchedPatternRenderer} whose construction params are irrelevant to scatter-add. */
	private BatchedPatternRenderer renderer(int noteCount, int rowLength) {
		return new BatchedPatternRenderer(noteCount, rowLength, rowLength, OutputLine.sampleRate, 2);
	}

	private PackedCollection collection(double... values) {
		PackedCollection c = new PackedCollection(values.length);
		c.setMem(values);
		return c;
	}

	/**
	 * Distinct per-note rows placed at distinct offsets, with one row truncated at
	 * the window edge. Offsets {0, 2, 5}, rowLength 4, windowWidth 8:
	 * <ul>
	 *   <li>note 0 [1,2,3,4]   at 0 → frames 0..3</li>
	 *   <li>note 1 [10,20,30,40] at 2 → frames 2..5</li>
	 *   <li>note 2 [100,200,300,400] at 5 → frames 5..7 (400 truncated past W=8)</li>
	 * </ul>
	 * Expected: [1, 2, 13, 24, 30, 140, 200, 300].
	 */
	@Test(timeout = 120000)
	@TestDepth(1)
	public void testScatterPlacementAndTruncation() {
		int noteCount = 3;
		int rowLength = 4;
		int windowWidth = 8;

		PackedCollection rows = new PackedCollection(shape(noteCount, rowLength));
		rows.setMem(new double[] {
				1, 2, 3, 4,
				10, 20, 30, 40,
				100, 200, 300, 400
		});
		PackedCollection destOffsets = collection(0, 2, 5);

		PackedCollection out = renderer(noteCount, rowLength)
				.buildScatterAdd(rows, destOffsets, noteCount, rowLength, windowWidth)
				.get().evaluate();

		double[] expected = { 1, 2, 13, 24, 30, 140, 200, 300 };
		for (int i = 0; i < windowWidth; i++) {
			Assert.assertEquals("frame " + i, expected[i], out.toDouble(i), 1e-6);
		}
	}

	/**
	 * Alignment-equivalence: with every destination offset 0 and
	 * {@code rowLength == windowWidth}, scatter-add must equal the plain
	 * column-sum that {@link BatchedPatternRenderer#buildBatchedChain}'s aligned
	 * reduction performs. Rows [1,2,3,4] and [5,6,7,8] → [6, 8, 10, 12].
	 */
	@Test(timeout = 120000)
	@TestDepth(1)
	public void testAlignedReduceEquivalence() {
		int noteCount = 2;
		int rowLength = 4;
		int windowWidth = 4;

		PackedCollection rows = new PackedCollection(shape(noteCount, rowLength));
		rows.setMem(new double[] {
				1, 2, 3, 4,
				5, 6, 7, 8
		});
		PackedCollection destOffsets = collection(0, 0);

		PackedCollection out = renderer(noteCount, rowLength)
				.buildScatterAdd(rows, destOffsets, noteCount, rowLength, windowWidth)
				.get().evaluate();

		double[] expected = { 6, 8, 10, 12 };
		for (int i = 0; i < windowWidth; i++) {
			Assert.assertEquals("frame " + i, expected[i], out.toDouble(i), 1e-6);
		}
	}
}
