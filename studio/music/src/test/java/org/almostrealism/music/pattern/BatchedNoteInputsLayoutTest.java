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

package org.almostrealism.music.pattern;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the static layout of the batched pattern renderer: the note-count
 * buckets of {@link BatchedPatternLayerRenderer} and the scalar column layout of
 * {@link BatchedNoteInputs}.
 */
public class BatchedNoteInputsLayoutTest extends TestSuiteBase {

	/** The batched bucket is the smallest configured bucket that fits the note count. */
	@Test(timeout = 10000)
	public void batchedBucketFitsNoteCount() {
		Assert.assertEquals(64, BatchedPatternLayerRenderer.bucketFor(0));
		Assert.assertEquals(64, BatchedPatternLayerRenderer.bucketFor(64));
		Assert.assertEquals(128, BatchedPatternLayerRenderer.bucketFor(65));
		Assert.assertEquals(512, BatchedPatternLayerRenderer.bucketFor(300));
		Assert.assertEquals("oversized counts use the largest bucket",
				512, BatchedPatternLayerRenderer.bucketFor(10000));

		BatchedPatternLayerRenderer renderer = new BatchedPatternLayerRenderer(44100, 3);
		Assert.assertEquals(44100, renderer.getSampleRate());
		Assert.assertEquals(3, renderer.getFilterOrder());
	}

	/** Batched scalar columns are laid out as nine per layer followed by the filter and volume envelopes. */
	@Test(timeout = 10000)
	public void batchedScalarLayoutIsDisjoint() {
		boolean[] used = new boolean[BatchedNoteInputs.SCALARS];

		for (int l = 0; l < BatchedNoteInputs.LAYERS; l++) {
			mark(used, BatchedNoteInputs.ratioIndex(l));
			for (int p = 0; p < 8; p++) {
				mark(used, BatchedNoteInputs.layerEnvIndex(l, p));
			}
		}

		for (int p = 0; p < 5; p++) {
			mark(used, BatchedNoteInputs.filterAdsrIndex(p));
			mark(used, BatchedNoteInputs.volumeAdsrIndex(p));
		}

		for (int i = 0; i < used.length; i++) {
			Assert.assertTrue("column " + i + " is unused", used[i]);
		}
	}

	/**
	 * Marks a scalar column as used, failing if it was already claimed.
	 *
	 * @param used  the per-column usage flags
	 * @param index the column to mark
	 */
	private static void mark(boolean[] used, int index) {
		Assert.assertFalse("column " + index + " is claimed twice", used[index]);
		used[index] = true;
	}
}
