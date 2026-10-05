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

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Next-token prediction windows over a contiguous region of a token sequence, for training
 * causal language models.
 *
 * <p>A window starting at source offset {@code s} takes its input from tokens
 * {@code [s, s + seqLen)}, as a {@code (seqLen)} collection of token ids, and its target from
 * tokens {@code [s + 1, s + seqLen + 1)}, as {@code (seqLen, vocabSize)} one-hot rows: target row
 * {@code t} is the one-hot of token {@code s + t + 1}. A window therefore needs
 * {@code seqLen + 1} tokens inside the region, and windows start every {@code stride} tokens from
 * the start of the region, up to an optional cap on the number of windows.</p>
 *
 * <h2>Held-out splits</h2>
 * <p>{@link #split(double)} partitions the <em>source tokens</em> into two disjoint contiguous
 * regions first and only then forms windows within each region, so no window of either part ever
 * reads a token (input or target) from the other part. Splitting after windowing, as the general
 * {@link Dataset#split(double)} does, would let a training window and a held-out window overlap in
 * up to {@code seqLen} tokens and leak the held-out targets into training. Windows that would
 * straddle the seam are dropped, so every held-out position, including the first one of each
 * held-out window, is scored on a prediction that never saw held-out tokens during training.</p>
 *
 * <h2>Window cap and rotation</h2>
 * <p>With a cap on the number of windows, every pass (epoch) normally yields the same first
 * {@code maxWindows} windows. A {@link #setRotating(boolean) rotating} dataset instead continues
 * where the previous pass stopped, cycling through every window of the region, so a capped epoch
 * stays short while successive epochs see different text. Datasets used for evaluation should not
 * rotate, so that every evaluation scores the same windows.</p>
 *
 * <p>The one-hot targets are built by a single assignment, compiled once for the dataset and re-run
 * over each window's ids, whose result is copied into that window's target. Window collections
 * are allocated per position within a pass, when first needed, and reused by every later pass,
 * so the dataset never holds more than {@link #getWindowCount()} windows. A
 * dataset that does not rotate yields the same windows on every pass and builds each only once; a
 * rotating dataset rewrites a position's collections in place when that position moves on to a
 * different window. A window therefore stays valid only until the same position of a later pass
 * is reached, which suits consumers that use each window as it is iterated, such as
 * {@link org.almostrealism.optimize.ModelOptimizer}.</p>
 *
 * <p>The dataset owns every collection it allocates, including the windows it yields, and releases
 * them all when {@link #destroy() destroyed}. A window must not be used after its dataset has been
 * destroyed; a later pass allocates fresh collections.</p>
 *
 * @see org.almostrealism.optimize.ModelOptimizer
 */
public class NextTokenDataset implements Dataset<PackedCollection>, Destroyable, CodeFeatures {
	/** The full source token sequence. */
	private final int[] tokens;

	/** First token of the region this dataset draws windows from. */
	private final int start;

	/** End (exclusive) of the region this dataset draws windows from. */
	private final int end;

	/** Number of classes, the width of every one-hot target row. */
	private final int vocabSize;

	/** Number of positions per window. */
	private final int seqLen;

	/** Distance between the starts of consecutive windows. */
	private final int stride;

	/** Maximum number of windows, or a non-positive value for no cap. */
	private final int maxWindows;

	/** Whether successive passes continue through the windows rather than restarting. */
	private boolean rotating;

	/** Index of the window the next rotating pass starts at. */
	private int nextWindow;

	/** The tokens of the region, loaded once when the first window is built. */
	private PackedCollection regionTokens;

	/** Lazily allocated window collections by position within a pass, reused across passes. */
	private List<ValueTarget<PackedCollection>> slots;

	/** Index of the window whose contents each slot currently holds. */
	private int[] slotWindows;

	/** The shifted ids of the window being written: the input of the one-hot assignment. */
	private PackedCollection shifted;

	/** The one-hot rows of the window being written: the output of the one-hot assignment. */
	private PackedCollection oneHot;

	/**
	 * The one-hot assignment from {@link #shifted} to {@link #oneHot}, compiled once, when the
	 * first window is written, and re-run for every window written after it.
	 */
	private Runnable oneHotTarget;

	/**
	 * Creates a dataset over the whole token sequence.
	 *
	 * @param tokens     the token ids, each in {@code 0..vocabSize-1}
	 * @param vocabSize  the number of classes
	 * @param seqLen     the number of positions per window
	 * @param stride     the distance between consecutive window starts
	 * @param maxWindows the maximum number of windows, or a non-positive value for no cap
	 */
	public NextTokenDataset(int[] tokens, int vocabSize, int seqLen, int stride, int maxWindows) {
		this(tokens, 0, tokens.length, vocabSize, seqLen, stride, maxWindows);
	}

	/**
	 * Creates a dataset over the region {@code [start, end)} of the token sequence. The tokens
	 * are copied once they have been validated, so later changes to {@code tokens} do not affect
	 * the dataset.
	 *
	 * @param tokens     the token ids, each in {@code 0..vocabSize-1}
	 * @param start      the first token of the region
	 * @param end        the end (exclusive) of the region
	 * @param vocabSize  the number of classes
	 * @param seqLen     the number of positions per window
	 * @param stride     the distance between consecutive window starts
	 * @param maxWindows the maximum number of windows, or a non-positive value for no cap
	 * @throws IllegalArgumentException if the region is not within the sequence, the vocabulary
	 *                                  size, window length or stride is not positive, or a token
	 *                                  of the region is not in {@code 0..vocabSize-1}
	 */
	public NextTokenDataset(int[] tokens, int start, int end, int vocabSize,
							int seqLen, int stride, int maxWindows) {
		if (start < 0 || end > tokens.length || start > end) {
			throw new IllegalArgumentException("Region [" + start + ", " + end +
					") is not within a sequence of " + tokens.length + " tokens");
		}

		if (vocabSize <= 0 || seqLen <= 0 || stride <= 0) {
			throw new IllegalArgumentException("Vocabulary size, window length and stride must be positive");
		}

		for (int i = start; i < end; i++) {
			if (tokens[i] < 0 || tokens[i] >= vocabSize) {
				throw new IllegalArgumentException("Token " + tokens[i] + " at position " + i +
						" is outside the vocabulary [0, " + vocabSize + ")");
			}
		}

		this.tokens = tokens.clone();
		this.start = start;
		this.end = end;
		this.vocabSize = vocabSize;
		this.seqLen = seqLen;
		this.stride = stride;
		this.maxWindows = maxWindows;
	}

	/**
	 * Creates a dataset over the region {@code [start, end)} of another dataset's tokens, with the
	 * same vocabulary size, window length, stride and window cap. The token array is shared rather
	 * than copied: it is owned by the datasets, never modified, and was validated over the parent's
	 * region, which contains this one.
	 *
	 * @param parent the dataset whose tokens and configuration are shared
	 * @param start  the first token of the region, within the parent's region
	 * @param end    the end (exclusive) of the region, within the parent's region
	 */
	private NextTokenDataset(NextTokenDataset parent, int start, int end) {
		this.tokens = parent.tokens;
		this.start = start;
		this.end = end;
		this.vocabSize = parent.vocabSize;
		this.seqLen = parent.seqLen;
		this.stride = parent.stride;
		this.maxWindows = parent.maxWindows;
	}

	/**
	 * Returns the first token of this dataset's region.
	 *
	 * @return the region start
	 */
	public int getStart() {
		return start;
	}

	/**
	 * Returns the end (exclusive) of this dataset's region.
	 *
	 * @return the region end
	 */
	public int getEnd() {
		return end;
	}

	/**
	 * Returns the number of positions per window.
	 *
	 * @return the window length
	 */
	public int getSeqLen() {
		return seqLen;
	}

	/**
	 * Returns the number of windows in the region: one per {@code stride} tokens from the region
	 * start for which all {@code seqLen + 1} tokens lie inside the region.
	 *
	 * @return the number of distinct windows
	 */
	public int getAvailableWindowCount() {
		int available = end - start - seqLen - 1;
		return available < 0 ? 0 : available / stride + 1;
	}

	/**
	 * Returns the number of windows yielded by each pass over this dataset: the available
	 * windows, capped at the maximum window count.
	 *
	 * @return the window count per pass
	 */
	public int getWindowCount() {
		int count = getAvailableWindowCount();
		return maxWindows > 0 ? Math.min(count, maxWindows) : count;
	}

	/**
	 * Sets whether successive passes continue through the windows where the previous pass
	 * stopped (cycling back to the first window after the last) instead of restarting at the
	 * first window. This only matters when the window count is capped.
	 *
	 * @param rotating whether passes rotate through the windows
	 * @return this dataset
	 */
	public NextTokenDataset setRotating(boolean rotating) {
		this.rotating = rotating;
		return this;
	}

	/**
	 * Returns whether successive passes rotate through the windows.
	 *
	 * @return whether passes rotate
	 */
	public boolean isRotating() {
		return rotating;
	}

	/**
	 * Returns the source offset at which the given window starts.
	 *
	 * @param window the window index, in {@code 0..getAvailableWindowCount()-1}
	 * @return the offset of the window's first input token
	 */
	public int getWindowStart(int window) {
		return start + window * stride;
	}

	/**
	 * Splits the source region into two disjoint contiguous regions, the first holding the given
	 * fraction of the tokens, and forms windows separately within each. No window of either part
	 * reads any token of the other part; see the class documentation. Both parts share this
	 * dataset's token array instead of copying it.
	 *
	 * @param ratio the fraction of the region's tokens assigned to the first part
	 * @return the first-part dataset followed by the second-part dataset
	 * @throws IllegalArgumentException if {@code ratio} is not within {@code [0, 1]}
	 */
	@Override
	public List<Dataset<PackedCollection>> split(double ratio) {
		if (ratio < 0 || ratio > 1) {
			throw new IllegalArgumentException("Split ratio " + ratio + " is not within [0, 1]");
		}

		int seam = start + (int) Math.round((end - start) * ratio);
		List<Dataset<PackedCollection>> parts = new ArrayList<>();
		parts.add(new NextTokenDataset(this, start, seam));
		parts.add(new NextTokenDataset(this, seam, end));
		return parts;
	}

	/**
	 * Returns the entropy, in bits per token, of the distribution of tokens in this dataset's
	 * region. A model that predicts every token from these frequencies alone, ignoring context,
	 * scores exactly this cross-entropy on the region, so it is the unigram baseline a context
	 * model has to beat.
	 *
	 * @return the unigram entropy in bits per token
	 */
	public double unigramEntropyBits() {
		long[] counts = new long[vocabSize];
		for (int i = start; i < end; i++) {
			counts[tokens[i]]++;
		}

		return entropyBits(counts, end - start);
	}

	/**
	 * Returns the entropy, in bits per token, of the distribution of the target tokens scored by
	 * one pass over this dataset: the targets of the first {@link #getWindowCount()} windows, with
	 * a token counted once for every window that scores it. The mean loss over the windows of a
	 * pass weighs every (window, position) equally, so a model that predicts every scored token
	 * from the frequencies of exactly these targets, ignoring context, scores this cross-entropy
	 * on the same tokens the pass scores. When the window count is capped, this is the unigram
	 * baseline that the loss of a pass is comparable to; {@link #unigramEntropyBits()} describes
	 * the whole region instead. For a {@link #setRotating(boolean) rotating} dataset it describes
	 * the first pass.
	 *
	 * @return the unigram entropy of the scored targets in bits per token
	 */
	public double scoredTargetEntropyBits() {
		long[] counts = new long[vocabSize];
		int count = getWindowCount();
		for (int w = 0; w < count; w++) {
			int s = getWindowStart(w);
			for (int i = s + 1; i <= s + seqLen; i++) {
				counts[tokens[i]]++;
			}
		}

		return entropyBits(counts, (double) count * seqLen);
	}

	/**
	 * Returns the entropy, in bits, of the distribution given by the token counts.
	 *
	 * @param counts the number of occurrences of each token
	 * @param total  the sum of the counts
	 * @return the entropy in bits
	 */
	private static double entropyBits(long[] counts, double total) {
		double entropy = 0.0;
		for (long count : counts) {
			if (count > 0) {
				double p = count / total;
				entropy -= p * Math.log(p) / Math.log(2.0);
			}
		}

		return entropy;
	}

	/**
	 * Returns the windows of the next pass: the first {@link #getWindowCount()} windows, or for
	 * a rotating dataset the next {@link #getWindowCount()} windows after those of the previous
	 * pass. Each window is built only when the iterator reaches it, so starting a pass over a
	 * large region allocates nothing beyond the windows actually consumed. The rotation advances
	 * when the iterator is created.
	 *
	 * @return an iterator over the windows of this pass
	 */
	@Override
	public Iterator<ValueTarget<PackedCollection>> iterator() {
		int available = getAvailableWindowCount();
		int count = getWindowCount();
		int first = rotating && available > 0 ? nextWindow % available : 0;

		if (rotating && available > 0) {
			nextWindow = (first + count) % available;
		}

		return IntStream.range(0, count).mapToObj(i -> window(i, (first + i) % available)).iterator();
	}

	/**
	 * Returns the collections of the given position within a pass, holding the window with the
	 * given index. The position's collections are allocated the first time it is reached and
	 * rewritten only when the position last held a different window. Every target is written by
	 * the same one-hot assignment, compiled when the first window is written: the window's shifted
	 * ids are copied into {@link #shifted}, the assignment is run, and its {@link #oneHot} result
	 * is copied into the position's target, so neither later positions nor rotating passes
	 * compile anything.
	 *
	 * @param position the position within the pass, in {@code 0..getWindowCount()-1}
	 * @param index    the window index, in {@code 0..getAvailableWindowCount()-1}
	 * @return the window
	 */
	private ValueTarget<PackedCollection> window(int position, int index) {
		if (slots == null) {
			slots = new ArrayList<>(Collections.nCopies(getWindowCount(), null));
			slotWindows = new int[getWindowCount()];
			shifted = new PackedCollection(shape(seqLen));
			oneHot = new PackedCollection(shape(seqLen, vocabSize));
			oneHotTarget = a(cp(oneHot.each()), oneHotRows(vocabSize, cp(shifted)).each()).get();
		}

		ValueTarget<PackedCollection> slot = slots.get(position);
		if (slot == null) {
			slot = ValueTarget.of(new PackedCollection(shape(seqLen)),
					new PackedCollection(shape(seqLen, vocabSize)));
			slots.set(position, slot);
		} else if (slotWindows[position] == index) {
			return slot;
		}

		int offset = getWindowStart(index) - start;
		slot.getInput().setFrom(0, regionTokens(), offset, seqLen);
		shifted.setFrom(0, regionTokens(), offset + 1, seqLen);
		oneHotTarget.run();
		slot.getExpectedOutput().setFrom(0, oneHot);

		slotWindows[position] = index;
		return slot;
	}

	/**
	 * Releases everything this dataset has allocated: the region's tokens, the compiled one-hot
	 * assignment followed by its input and output, and the input and target of every window
	 * yielded so far.
	 * Calling this more than once has no further effect, and a pass started afterwards allocates
	 * and builds its windows again.
	 */
	@Override
	public void destroy() {
		if (slots != null) {
			for (ValueTarget<PackedCollection> slot : slots) {
				if (slot != null) {
					Destroyable.destroy(List.of(slot.getInput(), slot.getExpectedOutput()));
				}
			}
		}

		Destroyable.destroy(Arrays.asList(oneHotTarget, shifted, oneHot, regionTokens));
		slots = null;
		slotWindows = null;
		oneHotTarget = null;
		shifted = null;
		oneHot = null;
		regionTokens = null;
	}

	/**
	 * Returns the tokens of the region as a collection, loading them the first time. The token
	 * ids are data entering the system from outside (the tokenized text), so they are ingested
	 * once, in a single transfer, through {@link PackedCollection#read(ByteBuffer)}.
	 *
	 * @return the region's tokens, shape {@code (end - start)}
	 */
	private PackedCollection regionTokens() {
		if (regionTokens == null) {
			ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES * (end - start));
			for (int i = start; i < end; i++) {
				buffer.putDouble(tokens[i]);
			}

			regionTokens = new PackedCollection(shape(end - start));
			regionTokens.read(buffer.flip());
		}

		return regionTokens;
	}
}
