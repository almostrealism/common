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

package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Tests for {@link NextTokenDataset}: the one-token target shift at both ends of every window,
 * the window count and region bound, the leak-free train/held-out split, and the unigram
 * baseline.
 *
 * <p>The token sequences use each position's index as its token id, so every id read from a
 * window identifies exactly which source position it came from.</p>
 */
public class NextTokenDatasetTest extends TestSuiteBase {
	/** Vocabulary large enough for every position index used. */
	private static final int VOCAB = 64;

	/**
	 * Input row {@code t} of a window starting at {@code s} is token {@code s + t}, and target row
	 * {@code t} is the one-hot of token {@code s + t + 1}, at row 0 and at row {@code seqLen - 1}
	 * as well as in between; no target reaches past the region end.
	 */
	@Test(timeout = 60000)
	public void targetsShiftByOneToken() {
		int length = 20;
		int seqLen = 4;
		NextTokenDataset data = new NextTokenDataset(positions(length), VOCAB, seqLen, 3, 0);
		Assert.assertEquals((length - seqLen - 1) / 3 + 1, data.getWindowCount());

		int w = 0;
		for (ValueTarget<PackedCollection> window : data) {
			int s = data.getWindowStart(w++);
			PackedCollection input = window.getInput();
			PackedCollection target = window.getExpectedOutput();
			Assert.assertEquals(seqLen, input.getShape().getTotalSize());
			Assert.assertEquals(seqLen * VOCAB, target.getShape().getTotalSize());

			for (int t = 0; t < seqLen; t++) {
				Assert.assertEquals(s + t, (int) input.toDouble(t));
				Assert.assertEquals("window " + s + " row " + t, s + t + 1, hotIndex(target, t));
			}

			Assert.assertEquals(s + 1, hotIndex(target, 0));
			Assert.assertEquals(s + seqLen, hotIndex(target, seqLen - 1));
			Assert.assertTrue(s + seqLen < length);
		}

		Assert.assertEquals(data.getWindowCount(), w);
	}

	/**
	 * After {@link NextTokenDataset#split}, every input and target token of a training window
	 * comes from the training region and every one of a held-out window from the held-out
	 * region, so no window crosses the seam.
	 */
	@Test(timeout = 60000)
	public void splitWindowsStayInTheirRegion() {
		int length = 50;
		int seqLen = 6;
		NextTokenDataset data = new NextTokenDataset(positions(length), VOCAB, seqLen, 2, 0);
		List<Dataset<PackedCollection>> parts = data.split(0.6);
		NextTokenDataset train = (NextTokenDataset) parts.get(0);
		NextTokenDataset heldOut = (NextTokenDataset) parts.get(1);
		int seam = 30;
		Assert.assertEquals(seam, train.getEnd());
		Assert.assertEquals(seam, heldOut.getStart());
		Assert.assertTrue(train.getWindowCount() > 0);
		Assert.assertTrue(heldOut.getWindowCount() > 0);

		for (ValueTarget<PackedCollection> window : train) {
			for (int t = 0; t < seqLen; t++) {
				Assert.assertTrue(window.getInput().toDouble(t) < seam);
				Assert.assertTrue(hotIndex(window.getExpectedOutput(), t) < seam);
			}
		}

		for (ValueTarget<PackedCollection> window : heldOut) {
			for (int t = 0; t < seqLen; t++) {
				Assert.assertTrue(window.getInput().toDouble(t) >= seam);
				Assert.assertTrue(hotIndex(window.getExpectedOutput(), t) >= seam);
			}
		}
	}

	/** A window cap limits the window count; a region too short for one window yields none. */
	@Test(timeout = 60000)
	public void windowCapAndShortRegion() {
		Assert.assertEquals(2, new NextTokenDataset(positions(40), VOCAB, 4, 4, 2).getWindowCount());
		Assert.assertEquals(0, new NextTokenDataset(positions(4), VOCAB, 4, 1, 0).getWindowCount());
		Assert.assertEquals(1, new NextTokenDataset(positions(5), VOCAB, 4, 1, 0).getWindowCount());
	}

	/**
	 * A capped dataset restarts at the first window on every pass unless it rotates, in which
	 * case each pass continues after the previous one and wraps around to the first window.
	 */
	@Test(timeout = 60000)
	public void rotatingPassesCycleThroughWindows() {
		NextTokenDataset fixed = new NextTokenDataset(positions(30), VOCAB, 4, 5, 2);
		Assert.assertEquals(List.of(0, 5), passStarts(fixed));
		Assert.assertEquals(List.of(0, 5), passStarts(fixed));

		NextTokenDataset rotating = new NextTokenDataset(positions(30), VOCAB, 4, 5, 2).setRotating(true);
		Assert.assertEquals(6, rotating.getAvailableWindowCount());
		Assert.assertEquals(List.of(0, 5), passStarts(rotating));
		Assert.assertEquals(List.of(10, 15), passStarts(rotating));
		Assert.assertEquals(List.of(20, 25), passStarts(rotating));
		Assert.assertEquals(List.of(0, 5), passStarts(rotating));

		NextTokenDataset uneven = new NextTokenDataset(positions(30), VOCAB, 4, 5, 4).setRotating(true);
		Assert.assertEquals(List.of(0, 5, 10, 15), passStarts(uneven));
		Assert.assertEquals(List.of(20, 25, 0, 5), passStarts(uneven));
	}

	/**
	 * Returns the first input token of every window of one pass, which with position-valued
	 * tokens is the window's start offset.
	 *
	 * @param data the dataset
	 * @return the window starts of the pass
	 */
	private List<Integer> passStarts(NextTokenDataset data) {
		List<Integer> starts = new ArrayList<>();
		for (ValueTarget<PackedCollection> window : data) {
			starts.add((int) window.getInput().toDouble(0));
		}

		return starts;
	}

	/** The unigram entropy of two equally frequent tokens is one bit. */
	@Test(timeout = 60000)
	public void unigramEntropy() {
		NextTokenDataset data = new NextTokenDataset(new int[] { 0, 1, 1, 0, 1, 0 }, VOCAB, 2, 1, 0);
		Assert.assertEquals(1.0, data.unigramEntropyBits(), 1e-12);
	}

	/**
	 * Returns the sequence {@code 0, 1, ..., length - 1}.
	 *
	 * @param length the sequence length
	 * @return the positions as token ids
	 */
	private int[] positions(int length) {
		return IntStream.range(0, length).toArray();
	}

	/**
	 * Returns the index of the single hot entry of a one-hot target row, failing if the row is
	 * not exactly one-hot.
	 *
	 * @param target the {@code (seqLen, vocab)} targets
	 * @param row    the row
	 * @return the hot index
	 */
	private int hotIndex(PackedCollection target, int row) {
		int hot = -1;
		for (int i = 0; i < VOCAB; i++) {
			double value = target.toDouble(row * VOCAB + i);
			if (value == 1.0) {
				Assert.assertEquals("row " + row + " has more than one hot entry", -1, hot);
				hot = i;
			} else {
				Assert.assertEquals(0.0, value, 0.0);
			}
		}

		Assert.assertTrue("row " + row + " has no hot entry", hot >= 0);
		return hot;
	}
}
