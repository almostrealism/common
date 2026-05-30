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
		CollectionProducer resampled2D =
				resampleFlat(batchedSource, ratios).reshape(shape(n, targetLength));
		CollectionProducer voiced2D = filterVolume2D(resampled2D, filterCutoffs, volumeEnvelopes);
		return reduceAligned(voiced2D);
	}

	/**
	 * Builds the batched three-source-sum (SSS) chain — the production melodic
	 * note shape. Each of the
	 * {@code sources.length} layers is independently resampled by its own
	 * per-note ratio, multiplied by its per-layer envelope, and the layers are
	 * summed — the {@code SOURCE, SOURCE, SOURCE} aggregation, the only
	 * {@code NoteAudioSourceAggregator} strategy reachable in production. The
	 * merged signal then flows through the shared filter-envelope and
	 * volume-envelope stages and the aligned reduction.
	 *
	 * <p>{@code sources}, {@code ratios}, and {@code layerEnvelopes} are parallel
	 * arrays, one entry per layer. The returned producer is uncompiled; no
	 * {@code evaluate()} occurs here.</p>
	 *
	 * @param sources         per-layer flattened sources, each shape {@code [n, sourceLength]}
	 * @param ratios          per-layer per-note pitch ratios, each shape {@code [n]}
	 * @param layerEnvelopes  per-layer per-note per-sample envelopes, each shape {@code [n, targetLength]}
	 * @param filterCutoffs   post-merge per-note cutoff envelopes, shape {@code [n, targetLength]}
	 * @param volumeEnvelopes post-merge per-note volume envelopes, shape {@code [n, targetLength]}
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [targetLength]}
	 * @throws IllegalArgumentException if {@code sources} is empty
	 */
	public CollectionProducer buildBatchedSssChain(PackedCollection[] sources,
												   PackedCollection[] ratios,
												   PackedCollection[] layerEnvelopes,
												   PackedCollection filterCutoffs,
												   PackedCollection volumeEnvelopes) {
		return reduceAligned(voicedSss(sources, ratios, layerEnvelopes,
				filterCutoffs, volumeEnvelopes));
	}

	/**
	 * Builds the batched three-source-sum (SSS) chain with offset-aware
	 * placement: identical to {@link #buildBatchedSssChain} through the
	 * volume-envelope stage, but the per-note {@code [targetLength]} voiced rows
	 * are scattered into a {@code [windowWidth]} output at their per-note
	 * destination offsets (the same mechanism as {@link #buildScatterAdd})
	 * instead of being summed in alignment. This is the full first-cut real-time
	 * a2 form: one fused kernel from three source layers to a placed, summed
	 * output window. No intermediate buffer is materialized between the chain and
	 * the placement.
	 *
	 * @param sources         per-layer flattened sources, each shape {@code [n, sourceLength]}
	 * @param ratios          per-layer per-note pitch ratios, each shape {@code [n]}
	 * @param layerEnvelopes  per-layer per-note per-sample envelopes, each shape {@code [n, targetLength]}
	 * @param filterCutoffs   post-merge per-note cutoff envelopes, shape {@code [n, targetLength]}
	 * @param volumeEnvelopes post-merge per-note volume envelopes, shape {@code [n, targetLength]}
	 * @param destOffsets     per-note destination offsets in frames, shape {@code [n]}
	 * @param windowWidth     width of the output window in frames
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [windowWidth]}
	 */
	public CollectionProducer buildBatchedSssChainPlaced(PackedCollection[] sources,
														 PackedCollection[] ratios,
														 PackedCollection[] layerEnvelopes,
														 PackedCollection filterCutoffs,
														 PackedCollection volumeEnvelopes,
														 PackedCollection destOffsets,
														 int windowWidth) {
		CollectionProducer voiced2D = voicedSss(sources, ratios, layerEnvelopes,
				filterCutoffs, volumeEnvelopes);
		return scatterAddFlat(voiced2D.reshape(shape(n * targetLength)),
				destOffsets, n, targetLength, windowWidth);
	}

	/**
	 * Shared SSS front + envelope stages: resamples each source layer by its
	 * per-note ratio, multiplies by its per-layer envelope, sums the layers, and
	 * applies the filter and volume envelopes. Used by both
	 * {@link #buildBatchedSssChain} (aligned reduce) and
	 * {@link #buildBatchedSssChainPlaced} (scatter placement).
	 *
	 * @param sources         per-layer flattened sources, each shape {@code [n, sourceLength]}
	 * @param ratios          per-layer per-note pitch ratios, each shape {@code [n]}
	 * @param layerEnvelopes  per-layer per-note per-sample envelopes, each shape {@code [n, targetLength]}
	 * @param filterCutoffs   post-merge per-note cutoff envelopes, shape {@code [n, targetLength]}
	 * @param volumeEnvelopes post-merge per-note volume envelopes, shape {@code [n, targetLength]}
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [n, targetLength]}
	 * @throws IllegalArgumentException if {@code sources} is empty
	 */
	private CollectionProducer voicedSss(PackedCollection[] sources,
										 PackedCollection[] ratios,
										 PackedCollection[] layerEnvelopes,
										 PackedCollection filterCutoffs,
										 PackedCollection volumeEnvelopes) {
		if (sources.length == 0) {
			throw new IllegalArgumentException("At least one source layer is required");
		}

		int totalSamples = n * targetLength;
		CollectionProducer merged = null;
		for (int i = 0; i < sources.length; i++) {
			CollectionProducer layer = resampleFlat(sources[i], ratios[i])
					.multiply(cp(layerEnvelopes[i]).reshape(shape(totalSamples)));
			merged = (merged == null) ? layer : merged.add(layer);
		}

		CollectionProducer merged2D = merged.reshape(shape(n, targetLength));
		return filterVolume2D(merged2D, filterCutoffs, volumeEnvelopes);
	}

	/**
	 * Kernel 1 — batched linear resample for one source layer. Maps each flat
	 * output index to {@code (noteIdx, sampleIdx)} and gathers from the note's
	 * source row with linear interpolation, using the per-note {@code ratios}.
	 *
	 * @param source flattened source audio, shape {@code [n, sourceLength]}
	 * @param ratios per-note pitch ratios, shape {@code [n]}
	 * @return an uncompiled {@link CollectionProducer} of flat shape {@code [n * targetLength]}
	 */
	private CollectionProducer resampleFlat(PackedCollection source, PackedCollection ratios) {
		int totalSamples = n * targetLength;

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
		CollectionProducer flatSource = cp(source).reshape(shape(n * sourceLength));
		CollectionProducer batchedS0 = c(shape(totalSamples), flatSource, srcIdx0);
		CollectionProducer batchedS1 = c(shape(totalSamples), flatSource, srcIdx1);
		return batchedS0.add(frac.multiply(batchedS1.subtract(batchedS0)));
	}

	/**
	 * Kernels 2 + 3 — padded-row FIR filter envelope followed by element-wise
	 * volume envelope, applied to an already-merged {@code [n, targetLength]}
	 * audio signal. Each row is padded by {@code filterOrder/2} zeros on each
	 * side so FIR boundary reads at row transitions land in padding rather than
	 * adjacent-note audio; the pad zones are trimmed afterward.
	 *
	 * @param audio2D         merged per-note audio, shape {@code [n, targetLength]}
	 * @param filterCutoffs   per-note per-sample cutoff envelopes, shape {@code [n, targetLength]}
	 * @param volumeEnvelopes per-note per-sample volume gain envelopes, shape {@code [n, targetLength]}
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [n, targetLength]}
	 */
	private CollectionProducer filterVolume2D(CollectionProducer audio2D,
											  PackedCollection filterCutoffs,
											  PackedCollection volumeEnvelopes) {
		int totalSamples = n * targetLength;
		int paddedNoteSize = targetLength + 2 * padHalf;
		int paddedTotal = n * paddedNoteSize;

		CollectionProducer paddedAudio2D = pad(audio2D, 0, padHalf);
		CollectionProducer paddedCutoff2D = pad(cp(filterCutoffs), 0, padHalf);
		CollectionProducer flatPaddedAudio = paddedAudio2D.reshape(shape(paddedTotal));
		CollectionProducer flatPaddedCutoff = paddedCutoff2D.reshape(shape(paddedTotal));
		CollectionProducer filtered =
				c(lowPass(traverseEach(flatPaddedAudio), flatPaddedCutoff, sampleRate, filterOrder));
		CollectionProducer filtered2D = filtered.reshape(shape(n, paddedNoteSize));
		CollectionProducer trimmed = subset(shape(n, targetLength), filtered2D, 0, padHalf);

		CollectionProducer flatTrimmed = trimmed.reshape(shape(totalSamples));
		CollectionProducer flatVolumeEnv = cp(volumeEnvelopes).reshape(shape(totalSamples));
		CollectionProducer voiced = flatTrimmed.multiply(flatVolumeEnv);
		return voiced.reshape(shape(n, targetLength));
	}

	/**
	 * Kernel 4 — aligned accumulate-reduce: sum across the note axis,
	 * {@code [n, targetLength] → [targetLength]}, by permuting to
	 * {@code [targetLength, n]} and reducing the note axis. This is the
	 * placement-free reduction; {@link #buildScatterAdd} is its offset-aware
	 * generalization.
	 *
	 * @param voiced2D per-note voiced audio, shape {@code [n, targetLength]}
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [targetLength]}
	 */
	private CollectionProducer reduceAligned(CollectionProducer voiced2D) {
		return permute(voiced2D, 1, 0).traverse(1).sum().reshape(shape(targetLength));
	}

	/**
	 * Builds an offset-aware scatter-add that places each note's
	 * {@code [rowLength]} row into a {@code [windowWidth]} output buffer at the
	 * note's per-note destination offset, summing overlaps into one window.
	 *
	 * <p>This is the batched form of the per-note ranged accumulate performed
	 * today by {@code PatternFeatures.sumToDestination}
	 * ({@code dest.range(shape, destOffset) += audio.range(shape, sourceOffset)}).
	 * It <em>generalizes</em> the aligned reduction at the tail of
	 * {@link #buildBatchedChain}: when every destination offset is {@code 0} and
	 * {@code rowLength == windowWidth}, this produces the same result as
	 * {@code permute([N, W]) → traverse(1).sum()}.</p>
	 *
	 * <p>For output frame {@code f} and note {@code n}, the contribution is
	 * {@code rows[n, f - destOffsets[n]]} when
	 * {@code 0 <= f - destOffsets[n] < rowLength}, and {@code 0} otherwise. A row
	 * whose placement runs past {@code windowWidth} is truncated — that audio
	 * belongs to a later window and is supplied on the next tick via the note's
	 * advancing sampling offset.</p>
	 *
	 * <p>The returned producer is uncompiled; no {@code evaluate()} occurs here.
	 * The index split uses {@code divide} (not multiply-by-reciprocal) so the
	 * integer {@code floor} is exact for any {@code windowWidth}.</p>
	 *
	 * @param rows        per-note rows packed row-by-row, shape
	 *                    {@code [noteCount, rowLength]}
	 * @param destOffsets per-note destination offsets in frames, shape
	 *                    {@code [noteCount]}
	 * @param noteCount   number of notes (rows)
	 * @param rowLength   samples per note row
	 * @param windowWidth width of the output window in frames
	 * @return an uncompiled {@link CollectionProducer} of shape
	 *         {@code [windowWidth]}
	 */
	public CollectionProducer buildScatterAdd(PackedCollection rows,
											  PackedCollection destOffsets,
											  int noteCount, int rowLength, int windowWidth) {
		return scatterAddFlat(cp(rows).reshape(shape(noteCount * rowLength)),
				destOffsets, noteCount, rowLength, windowWidth);
	}

	/**
	 * Core of {@link #buildScatterAdd} operating on an uncompiled {@code flatRows}
	 * producer (flat shape {@code [noteCount * rowLength]}) rather than a
	 * materialized buffer, so it can be fused directly onto an upstream chain
	 * (e.g. {@link #buildBatchedSssChainPlaced}) and compiled as a single kernel.
	 *
	 * @param flatRows    per-note rows, flat shape {@code [noteCount * rowLength]}
	 * @param destOffsets per-note destination offsets in frames, shape {@code [noteCount]}
	 * @param noteCount   number of notes (rows)
	 * @param rowLength   samples per note row
	 * @param windowWidth width of the output window in frames
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [windowWidth]}
	 */
	private CollectionProducer scatterAddFlat(CollectionProducer flatRows,
											  PackedCollection destOffsets,
											  int noteCount, int rowLength, int windowWidth) {
		int total = noteCount * windowWidth;

		// Flat intermediate index t in [0, noteCount * windowWidth), laid out
		// [noteCount, windowWidth]; the final reduction sums the note axis.
		CollectionProducer t = integers(0, total);
		CollectionProducer noteIdx = floor(t.divide(c((double) windowWidth)));
		CollectionProducer f = t.subtract(noteIdx.multiply(c((double) windowWidth)));

		// Per-row destination offset, gathered by note index.
		CollectionProducer destOff = c(shape(total), cp(destOffsets), noteIdx);
		// Position within the note's row that maps to output frame f.
		CollectionProducer localIdx = f.subtract(destOff);

		// Validity mask: 1 where 0 <= localIdx < rowLength, else 0.
		CollectionProducer geLow = localIdx.greaterThan(c(0.0), c(1.0), c(0.0), true);
		CollectionProducer ltHigh = localIdx.lessThan(c((double) rowLength), c(1.0), c(0.0));
		CollectionProducer mask = geLow.multiply(ltHigh);

		// Clamp the row position into range so the gather index is always valid;
		// the mask zeroes any out-of-range contribution.
		CollectionProducer clampedLocal = bound(localIdx, 0.0, (double) (rowLength - 1));
		CollectionProducer flatRowIdx =
				noteIdx.multiply(c((double) rowLength)).add(clampedLocal);

		CollectionProducer gathered = c(shape(total), flatRows, flatRowIdx);
		CollectionProducer contribution = gathered.multiply(mask);

		// Sum the note axis: [noteCount, windowWidth] → [windowWidth, noteCount]
		// → reduce → [windowWidth].
		CollectionProducer rows2D = contribution.reshape(shape(noteCount, windowWidth));
		return permute(rows2D, 1, 0).traverse(1).sum().reshape(shape(windowWidth));
	}
}
