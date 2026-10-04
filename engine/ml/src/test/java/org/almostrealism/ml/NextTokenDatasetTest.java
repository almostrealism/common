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
import java.util.Arrays;
import java.util.Iterator;
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
	 * A pass builds each window only when the iterator reaches it. This region has 100,000
	 * windows whose one-hot targets would take hundreds of gigabytes if a pass built them all
	 * up front; consuming the first two windows must build only those two, with correct
	 * contents, and a rotating pass still advances by the full window count.
	 */
	@Test(timeout = 120000)
	public void iteratorBuildsWindowsLazily() {
		int vocab = 4096;
		int seqLen = 64;
		int windows = 100000;
		int[] tokens = IntStream.range(0, windows + seqLen).map(i -> i % vocab).toArray();
		NextTokenDataset data = new NextTokenDataset(tokens, vocab, seqLen, 1, 0).setRotating(true);
		Assert.assertEquals(windows, data.getWindowCount());

		Iterator<ValueTarget<PackedCollection>> pass = data.iterator();
		for (int s = 0; s < 2; s++) {
			Assert.assertTrue(pass.hasNext());
			ValueTarget<PackedCollection> window = pass.next();
			Assert.assertEquals(seqLen * vocab, window.getExpectedOutput().getShape().getTotalSize());
			Assert.assertEquals(s, (int) window.getInput().toDouble(0));
			Assert.assertEquals(s + seqLen - 1, (int) window.getInput().toDouble(seqLen - 1));
			Assert.assertEquals(1.0, window.getExpectedOutput().toDouble(s + 1), 0.0);
			Assert.assertEquals(1.0,
					window.getExpectedOutput().toDouble((seqLen - 1) * vocab + s + seqLen), 0.0);
		}

		Assert.assertEquals(0, (int) data.iterator().next().getInput().toDouble(0));
	}

	/**
	 * The dataset holds at most one pass of windows: every pass returns the same per-position
	 * collections. A rotating pass rewrites them in place with the next windows, so no stale
	 * one-hot entry of an earlier window survives, and a dataset that does not rotate keeps the
	 * same contents on every pass.
	 */
	@Test(timeout = 60000)
	public void passesReuseOnePassOfWindows() {
		NextTokenDataset rotating = new NextTokenDataset(positions(30), VOCAB, 4, 5, 2).setRotating(true);
		List<ValueTarget<PackedCollection>> first = pass(rotating);
		Assert.assertEquals(2, first.size());
		assertWindow(first.get(0), 0);
		assertWindow(first.get(1), 5);

		for (int expected : new int[] { 10, 20, 0 }) {
			List<ValueTarget<PackedCollection>> next = pass(rotating);
			Assert.assertEquals(2, next.size());
			for (int p = 0; p < 2; p++) {
				Assert.assertSame(first.get(p), next.get(p));
				assertWindow(next.get(p), expected + 5 * p);
			}
		}

		NextTokenDataset fixed = new NextTokenDataset(positions(30), VOCAB, 4, 5, 2);
		List<ValueTarget<PackedCollection>> fixedFirst = pass(fixed);
		List<ValueTarget<PackedCollection>> fixedSecond = pass(fixed);
		for (int p = 0; p < 2; p++) {
			Assert.assertSame(fixedFirst.get(p), fixedSecond.get(p));
			assertWindow(fixedSecond.get(p), 5 * p);
		}
	}

	/**
	 * Every rewrite of a position re-runs the dataset's shared one-hot assignment over the new
	 * window's ids and copies the result into that position's target, which must not alias the
	 * shared result. With four positions over six windows the rotation wraps unevenly, so across
	 * seven passes each position holds several different windows, and each must be exact after
	 * every rewrite, including when every other position has been written since. A rotating
	 * dataset whose pass covers every window never rewrites a position.
	 */
	@Test(timeout = 60000)
	public void rewrittenPositionsRerunTheirAssignment() {
		NextTokenDataset uneven = new NextTokenDataset(positions(30), VOCAB, 4, 5, 4).setRotating(true);
		List<ValueTarget<PackedCollection>> first = pass(uneven);
		for (int p = 0; p < 4; p++) {
			assertWindow(first.get(p), 5 * p);
		}

		for (int n = 1; n < 7; n++) {
			List<ValueTarget<PackedCollection>> next = pass(uneven);
			for (int p = 0; p < 4; p++) {
				Assert.assertSame(first.get(p), next.get(p));
				assertWindow(next.get(p), 5 * ((4 * n + p) % 6));
			}
		}

		NextTokenDataset full = new NextTokenDataset(positions(30), VOCAB, 4, 5, 6).setRotating(true);
		List<ValueTarget<PackedCollection>> fullFirst = pass(full);
		for (int n = 0; n < 3; n++) {
			List<ValueTarget<PackedCollection>> next = pass(full);
			for (int p = 0; p < 6; p++) {
				Assert.assertSame(fullFirst.get(p), next.get(p));
				assertWindow(next.get(p), 5 * p);
			}
		}
	}

	/**
	 * Returns the windows of one pass, in order.
	 *
	 * @param data the dataset
	 * @return the windows of the pass
	 */
	private List<ValueTarget<PackedCollection>> pass(NextTokenDataset data) {
		List<ValueTarget<PackedCollection>> windows = new ArrayList<>();
		data.iterator().forEachRemaining(windows::add);
		return windows;
	}

	/**
	 * Asserts that a window over position-valued tokens starts at the given offset: input row
	 * {@code t} is {@code s + t} and target row {@code t} is exactly the one-hot of {@code s + t + 1}.
	 *
	 * @param window the window
	 * @param s      the expected start offset
	 */
	private void assertWindow(ValueTarget<PackedCollection> window, int s) {
		int seqLen = window.getInput().getShape().getTotalSize();
		for (int t = 0; t < seqLen; t++) {
			Assert.assertEquals(s + t, (int) window.getInput().toDouble(t));
			Assert.assertEquals("window " + s + " row " + t, s + t + 1, hotIndex(window.getExpectedOutput(), t));
		}
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
	 * The scored-target entropy counts only the targets of the windows a pass yields, once per
	 * window that scores them: with one capped window over {@code 0, 1, 1, 0, 1, 0} the scored
	 * targets are {@code 1, 1}, which carry no information, while the whole region is balanced.
	 */
	@Test(timeout = 60000)
	public void scoredTargetEntropy() {
		NextTokenDataset capped = new NextTokenDataset(new int[] { 0, 1, 1, 0, 1, 0 }, VOCAB, 2, 1, 1);
		Assert.assertEquals(0.0, capped.scoredTargetEntropyBits(), 1e-12);
		Assert.assertEquals(1.0, capped.unigramEntropyBits(), 1e-12);

		NextTokenDataset disjoint = new NextTokenDataset(new int[] { 0, 0, 1, 1, 0 }, VOCAB, 2, 2, 0);
		Assert.assertEquals(1.0, disjoint.scoredTargetEntropyBits(), 1e-12);
	}

	/** A token outside the vocabulary is rejected when the dataset is created. */
	@Test(timeout = 60000)
	public void rejectsTokenOutsideVocabulary() {
		assertRejected(new int[] { 0, 1, VOCAB });
		assertRejected(new int[] { 0, -1, 1 });
	}

	/**
	 * The dataset keeps its own copy of the validated tokens: overwriting the caller's array
	 * afterwards, even with ids outside the vocabulary, changes neither the windows nor the
	 * entropies.
	 */
	@Test(timeout = 60000)
	public void copiesTokensOnCreation() {
		int[] tokens = { 0, 1, 1, 0, 1, 0 };
		NextTokenDataset data = new NextTokenDataset(tokens, VOCAB, 2, 1, 0);
		Arrays.fill(tokens, VOCAB + 5);

		Assert.assertEquals(1.0, data.unigramEntropyBits(), 1e-12);
		Assert.assertEquals(4, data.getWindowCount());

		ValueTarget<PackedCollection> first = data.iterator().next();
		Assert.assertEquals(0, (int) first.getInput().toDouble(0));
		Assert.assertEquals(1, (int) first.getInput().toDouble(1));
		Assert.assertEquals(1, hotIndex(first.getExpectedOutput(), 0));
		Assert.assertEquals(1, hotIndex(first.getExpectedOutput(), 1));
	}

	/**
	 * Asserts that creating a dataset over the given tokens fails with an
	 * {@link IllegalArgumentException}.
	 *
	 * @param tokens the token ids, at least one outside the vocabulary
	 */
	private void assertRejected(int[] tokens) {
		try {
			new NextTokenDataset(tokens, VOCAB, 1, 1, 0);
			Assert.fail("tokens " + Arrays.toString(tokens) + " were accepted");
		} catch (IllegalArgumentException expected) {
			log("rejected=" + expected.getMessage());
		}
	}

	/**
	 * {@link NextTokenDataset#destroy()} releases the input and target of every window yielded
	 * so far, is safe to call again and on a dataset that never built a window, and a pass started
	 * after it builds fresh, correct windows.
	 */
	@Test(timeout = 60000)
	public void destroyReleasesWindows() {
		int seqLen = 4;
		NextTokenDataset unused = new NextTokenDataset(positions(20), VOCAB, seqLen, seqLen, 0);
		unused.destroy();
		unused.destroy();

		NextTokenDataset data = new NextTokenDataset(positions(20), VOCAB, seqLen, seqLen, 0);
		List<ValueTarget<PackedCollection>> first = new ArrayList<>();
		data.forEach(first::add);
		Assert.assertEquals(data.getWindowCount(), first.size());
		Assert.assertTrue(data.getWindowCount() > 1);

		data.destroy();
		for (ValueTarget<PackedCollection> window : first) {
			Assert.assertTrue(window.getInput().isDestroyed());
			Assert.assertTrue(window.getExpectedOutput().isDestroyed());
		}

		data.destroy();

		int w = 0;
		for (ValueTarget<PackedCollection> window : data) {
			Assert.assertFalse(window.getInput().isDestroyed());
			int s = data.getWindowStart(w++);
			Assert.assertEquals(s, (int) window.getInput().toDouble(0));
			Assert.assertEquals(s + 1, hotIndex(window.getExpectedOutput(), 0));
			Assert.assertEquals(s + seqLen, hotIndex(window.getExpectedOutput(), seqLen - 1));
		}

		Assert.assertEquals(data.getWindowCount(), w);
		data.destroy();
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
