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

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;

/**
 * Batched pattern renderer implementing the four-kernel chain used by
 * the Phase 3 pattern rendering architecture:
 * <ol>
 *   <li><b>Resample</b> — per-row linear interpolation gather from
 *       {@code [N, sourceLength]} source using {@code [N]} pitch ratios →
 *       {@code [N, targetLength]}</li>
 *   <li><b>Filter envelope</b> — padded-row FIR low-pass with per-sample
 *       per-row cutoff → {@code [N, targetLength]}</li>
 *   <li><b>Volume envelope</b> — element-wise multiply by per-row gain
 *       curves → {@code [N, targetLength]}</li>
 *   <li><b>Accumulate-reduce</b> — sum across the note axis →
 *       {@code [targetLength]}</li>
 * </ol>
 *
 * <p>All four kernels are expressed as a single composed
 * {@link CollectionProducer} that the framework compiles to one native kernel.
 * No {@code evaluate()} calls occur inside any of the builder methods.</p>
 *
 * <p>The construction parameters ({@code n}, {@code sourceLength},
 * {@code targetLength}, {@code sampleRate}, {@code filterOrder}) are fixed at
 * construction time; the data inputs ({@code batchedSource}, {@code ratios},
 * etc.) are passed at chain-build time as {@link PackedCollection}s captured
 * via {@code cp()} so the compiled kernel can be reused across ticks with
 * different data via {@code setMem}.</p>
 *
 * <p>Resample algorithm is identical to
 * {@code PatternRenderingFloorBenchmark.buildResampleProducer} and
 * {@code buildBatchedResampleVolume}, lifted verbatim into production code.</p>
 *
 * @see org.almostrealism.audio.filter.AudioProcessingUtils
 */
public class BatchedPatternRenderer implements CollectionFeatures, TemporalFeatures {

	/** Number of notes processed per batch. */
	private final int n;

	/** Source samples per note (before resampling). */
	private final int sourceLength;

	/** Target samples per note (after resampling). */
	private final int targetLength;

	/** Audio sample rate in Hz. */
	private final int sampleRate;

	/** FIR filter order used by the filter envelope kernel. */
	private final int filterOrder;

	/** Half the filter order; used for per-row padding in the filter kernel. */
	private final int padHalf;

	/**
	 * Constructs a renderer for batches of {@code n} notes.
	 *
	 * @param n            notes per batch
	 * @param sourceLength source samples per note before resampling
	 * @param targetLength target samples per note after resampling
	 * @param sampleRate   audio sample rate in Hz
	 * @param filterOrder  FIR filter order for the per-row lowpass kernel
	 */
	public BatchedPatternRenderer(int n, int sourceLength, int targetLength,
								  int sampleRate, int filterOrder) {
		this.n = n;
		this.sourceLength = sourceLength;
		this.targetLength = targetLength;
		this.sampleRate = sampleRate;
		this.filterOrder = filterOrder;
		this.padHalf = filterOrder / 2;
	}

	/**
	 * Builds a per-note linear-resample producer that maps a
	 * {@code [sourceLength]} source to {@code [targetLength]} via
	 * fractional-position lerp.
	 *
	 * <p>This is the single-note form used for per-note reference comparisons
	 * and as a building block. The algorithm is identical to
	 * {@code PatternRenderingFloorBenchmark.buildResampleProducer}.</p>
	 *
	 * @param source source audio buffer of length {@code sourceLength}
	 * @param ratio  resample ratio — reads source at {@code sample × ratio}
	 * @return an uncompiled {@link CollectionProducer} of shape
	 *         {@code [targetLength]}
	 */
	public CollectionProducer buildResampleProducer(PackedCollection source, double ratio) {
		CollectionProducer srcPos = integers(0, targetLength).multiply(c(ratio));
		CollectionProducer fPos = floor(srcPos);
		CollectionProducer frac = srcPos.subtract(fPos);
		CollectionProducer s0 = c(shape(targetLength), cp(source), fPos);
		CollectionProducer s1 = c(shape(targetLength), cp(source), fPos.add(c(1.0)));
		return s0.add(frac.multiply(s1.subtract(s0)));
	}

	/**
	 * Builds the full batched four-kernel chain for all {@code N} notes in a
	 * single {@link CollectionProducer}.
	 *
	 * <p>Chain layout:</p>
	 * <ol>
	 *   <li><b>Resample</b> — per-row linear interpolation gather from the
	 *       flattened {@code batchedSource} using {@code ratios[N]} →
	 *       intermediate {@code [N, targetLength]}</li>
	 *   <li><b>Filter envelope</b> — each row of the resampled audio is padded
	 *       by {@code filterOrder/2} zeros on each side, the batch is flattened,
	 *       a single {@code lowPass} call with the matching flattened
	 *       {@code filterCutoffs} is applied, and the pad zones are trimmed →
	 *       {@code [N, targetLength]}</li>
	 *   <li><b>Volume envelope</b> — element-wise multiply by the flattened
	 *       {@code volumeEnvelopes} → {@code [N, targetLength]}</li>
	 *   <li><b>Accumulate-reduce</b> — permute to {@code [targetLength, N]}
	 *       then {@code traverse(1).sum()} reduces to {@code [targetLength]}</li>
	 * </ol>
	 *
	 * <p>The returned producer is uncompiled; call {@code .get()} to compile
	 * and {@code .evaluate()} to run.</p>
	 *
	 * @param batchedSource   source audio packed row-by-row, shape
	 *                        {@code [N, sourceLength]}
	 * @param ratios          per-note pitch ratios, shape {@code [N]}
	 * @param filterCutoffs   per-note, per-sample cutoff envelopes, shape
	 *                        {@code [N, targetLength]}
	 * @param volumeEnvelopes per-note, per-sample volume gain envelopes, shape
	 *                        {@code [N, targetLength]}
	 * @return an uncompiled {@link CollectionProducer} of shape
	 *         {@code [targetLength]}
	 */
	public CollectionProducer buildBatchedChain(PackedCollection batchedSource,
												 PackedCollection ratios,
												 PackedCollection filterCutoffs,
												 PackedCollection volumeEnvelopes) {
		int totalSamples = n * targetLength;
		int paddedNoteSize = targetLength + 2 * padHalf;
		int paddedTotal = n * paddedNoteSize;

		// ── Kernel 1: batched resample ──────────────────────────────────────
		// Map each flat output index to (noteIdx, sampleIdx) and gather from
		// the note's source row with linear interpolation.

		CollectionProducer outIdx = integers(0, totalSamples);
		// noteIdx = floor(outIdx / targetLength)
		CollectionProducer noteIdx = floor(outIdx.multiply(c(1.0 / targetLength)));
		// sampleIdx = outIdx - noteIdx * targetLength
		CollectionProducer sampleIdx = outIdx.subtract(noteIdx.multiply(c((double) targetLength)));
		// Per-row ratio: ratios[noteIdx]
		CollectionProducer perNoteRatio = c(shape(totalSamples), cp(ratios), noteIdx);
		CollectionProducer srcPos = sampleIdx.multiply(perNoteRatio);
		CollectionProducer fPos = floor(srcPos);
		CollectionProducer frac = srcPos.subtract(fPos);
		// Compute absolute source indices within the flat [N * sourceLength] buffer
		CollectionProducer sourceBaseIdx = noteIdx.multiply(c((double) sourceLength));
		CollectionProducer srcIdx0 = sourceBaseIdx.add(fPos);
		CollectionProducer srcIdx1 = sourceBaseIdx.add(fPos.add(c(1.0)));
		CollectionProducer flatSource = cp(batchedSource).reshape(shape(n * sourceLength));
		CollectionProducer batchedS0 = c(shape(totalSamples), flatSource, srcIdx0);
		CollectionProducer batchedS1 = c(shape(totalSamples), flatSource, srcIdx1);
		CollectionProducer batchedResampled =
				batchedS0.add(frac.multiply(batchedS1.subtract(batchedS0)));
		CollectionProducer resampled2D = batchedResampled.reshape(shape(n, targetLength));

		// ── Kernel 2: filter envelope via padded-row lowPass ────────────────
		// Pad each row by padHalf zeros on each side so that FIR boundary
		// reads at row transitions fall into the padding rather than the audio
		// of an adjacent note.  The per-row pad zones are then trimmed after
		// the lowPass call to recover the [N, targetLength] shape.

		CollectionProducer paddedAudio2D = pad(resampled2D, 0, padHalf);
		CollectionProducer paddedCutoff2D = pad(cp(filterCutoffs), 0, padHalf);
		CollectionProducer flatPaddedAudio = paddedAudio2D.reshape(shape(paddedTotal));
		CollectionProducer flatPaddedCutoff = paddedCutoff2D.reshape(shape(paddedTotal));
		CollectionProducer filtered =
				c(lowPass(traverseEach(flatPaddedAudio), flatPaddedCutoff, sampleRate, filterOrder));
		CollectionProducer filtered2D = filtered.reshape(shape(n, paddedNoteSize));
		CollectionProducer trimmed = subset(shape(n, targetLength), filtered2D, 0, padHalf);

		// ── Kernel 3: volume envelope multiply ─────────────────────────────
		CollectionProducer flatTrimmed = trimmed.reshape(shape(totalSamples));
		CollectionProducer flatVolumeEnv = cp(volumeEnvelopes).reshape(shape(totalSamples));
		CollectionProducer voiced = flatTrimmed.multiply(flatVolumeEnv);
		CollectionProducer voiced2D = voiced.reshape(shape(n, targetLength));

		// ── Kernel 4: accumulate-reduce [N, targetLength] → [targetLength] ──
		// permute [N, targetLength] → [targetLength, N], then traverse(1).sum()
		// reduces each row of N notes to a single value.
		CollectionProducer permuted = permute(voiced2D, 1, 0);
		return permuted.traverse(1).sum().reshape(shape(targetLength));
	}
}
