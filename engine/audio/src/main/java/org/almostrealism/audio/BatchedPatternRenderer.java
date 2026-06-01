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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.audio.filter.MultiOrderFilterEnvelopeProcessor;
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

	/** Compile-once fully-fused SSS dispatch, reused across ticks; built on first {@link #sssDispatch}. */
	private Evaluable<PackedCollection> sssDispatch;

	/** Bound per-layer source buffers read by {@link #sssDispatch}, each {@code [n, sourceLength]}. */
	private PackedCollection[] sssSources;

	/** Bound per-layer pitch-ratio buffers, each {@code [n]}. */
	private PackedCollection[] sssRatios;

	/** Bound per-layer envelope-scalar buffers, {@code [layers][8]} each {@code [n]}. */
	private PackedCollection[][] sssLayerEnv;

	/** Bound filter-envelope scalar buffers, {@code [5]} each {@code [n]}. */
	private PackedCollection[] sssFilterAdsr;

	/** Bound volume-envelope scalar buffers, {@code [5]} each {@code [n]}. */
	private PackedCollection[] sssVolumeAdsr;

	/** Bound per-note destination offsets, {@code [n]}. */
	private PackedCollection sssDestOffsets;

	/** Bound per-note sampling offsets, {@code [n]}. */
	private PackedCollection sssSamplingOffsets;

	/**
	 * Returns the compile-once fully-fused SSS dispatch for this renderer's fixed
	 * shape, building and compiling it on first call and reusing it thereafter. The
	 * dispatch reads its inputs from this renderer's
	 * bound buffers (the {@code getSss*} accessors); a caller writes the per-tick
	 * data into those buffers with {@code setMem} and re-evaluates the returned
	 * {@link Evaluable}, so the native kernel is compiled once and reused every tick
	 * rather than rebuilt and recompiled per dispatch.
	 *
	 * @param layers the number of source layers (the SSS aggregation arity)
	 * @return the compiled, reusable dispatch evaluable producing a {@code [targetLength]} window
	 */
	public Evaluable<PackedCollection> sssDispatch(int layers) {
		if (sssDispatch == null) {
			sssSources = new PackedCollection[layers];
			sssRatios = new PackedCollection[layers];
			sssLayerEnv = new PackedCollection[layers][8];
			for (int l = 0; l < layers; l++) {
				sssSources[l] = new PackedCollection(n, sourceLength);
				sssRatios[l] = new PackedCollection(n);
				for (int p = 0; p < 8; p++) {
					sssLayerEnv[l][p] = new PackedCollection(n);
				}
			}
			sssFilterAdsr = new PackedCollection[5];
			sssVolumeAdsr = new PackedCollection[5];
			for (int p = 0; p < 5; p++) {
				sssFilterAdsr[p] = new PackedCollection(n);
				sssVolumeAdsr[p] = new PackedCollection(n);
			}
			sssDestOffsets = new PackedCollection(n);
			sssSamplingOffsets = new PackedCollection(n);

			CollectionProducer producer = buildBatchedSssChainPlacedFromScalars(
					sssSources, sssRatios, sssLayerEnv, sssFilterAdsr, sssVolumeAdsr,
					sssDestOffsets, sssSamplingOffsets, targetLength);
			// Plain get() (not Process.optimized) is used deliberately: the chain's
			// reshape nodes trip the optimizer's isolation pass, and the compile-once
			// win comes from caching this Evaluable, not from graph optimization.
			sssDispatch = producer.get();
		}
		return sssDispatch;
	}

	/** Returns the bound per-layer source buffers for the SSS dispatch. */
	public PackedCollection[] getSssSources() { return sssSources; }

	/** Returns the bound per-layer pitch-ratio buffers for the SSS dispatch. */
	public PackedCollection[] getSssRatios() { return sssRatios; }

	/** Returns the bound per-layer envelope-scalar buffers for the SSS dispatch. */
	public PackedCollection[][] getSssLayerEnv() { return sssLayerEnv; }

	/** Returns the bound filter-envelope scalar buffers for the SSS dispatch. */
	public PackedCollection[] getSssFilterAdsr() { return sssFilterAdsr; }

	/** Returns the bound volume-envelope scalar buffers for the SSS dispatch. */
	public PackedCollection[] getSssVolumeAdsr() { return sssVolumeAdsr; }

	/** Returns the bound per-note destination-offset buffer for the SSS dispatch. */
	public PackedCollection getSssDestOffsets() { return sssDestOffsets; }

	/** Returns the bound per-note sampling-offset buffer for the SSS dispatch. */
	public PackedCollection getSssSamplingOffsets() { return sssSamplingOffsets; }

	/** Returns the number of notes per batch (the bucket size). */
	public int getN() { return n; }

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
				resampleFlat(batchedSource, ratios, null).reshape(shape(n, targetLength));
		CollectionProducer voiced2D = filterVolume2D(resampled2D, cp(filterCutoffs), cp(volumeEnvelopes));
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
		return reduceAligned(voicedSss(sources, ratios, wrapCurves(layerEnvelopes),
				cp(filterCutoffs), cp(volumeEnvelopes), null));
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
		CollectionProducer voiced2D = voicedSss(sources, ratios, wrapCurves(layerEnvelopes),
				cp(filterCutoffs), cp(volumeEnvelopes), null);
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
										 CollectionProducer[] layerEnvelopes,
										 CollectionProducer filterCutoffs,
										 CollectionProducer volumeEnvelopes,
										 PackedCollection samplingOffsets) {
		if (sources.length == 0) {
			throw new IllegalArgumentException("At least one source layer is required");
		}

		int totalSamples = n * targetLength;
		CollectionProducer merged = null;
		for (int i = 0; i < sources.length; i++) {
			CollectionProducer layer = resampleFlat(sources[i], ratios[i], samplingOffsets)
					.multiply(layerEnvelopes[i].reshape(shape(totalSamples)));
			merged = (merged == null) ? layer : merged.add(layer);
		}

		CollectionProducer merged2D = merged.reshape(shape(n, targetLength));
		return filterVolume2D(merged2D, filterCutoffs, volumeEnvelopes);
	}

	/** Wraps each materialized envelope curve as a {@link CollectionProducer} via {@code cp}. */
	private CollectionProducer[] wrapCurves(PackedCollection[] curves) {
		CollectionProducer[] wrapped = new CollectionProducer[curves.length];
		for (int i = 0; i < curves.length; i++) {
			wrapped[i] = cp(curves[i]);
		}
		return wrapped;
	}

	/**
	 * Fully fused production form of {@link #buildBatchedSssChainPlaced}: generates
	 * the per-layer, filter-cutoff, and volume envelope curves <em>inside</em> the
	 * kernel from per-note ADSR scalar tensors (so no envelope curve is
	 * materialized per note), then runs the three-layer SSS chain and the
	 * offset-aware scatter placement as a single compiled dispatch. This is the
	 * entry point the production gather targets: it consumes only cheap per-note
	 * scalars, pitch ratios, source buffers, and offsets.
	 *
	 * <p>The filter-cutoff curve is the volume-envelope ADSR shape scaled to
	 * {@link MultiOrderFilterEnvelopeProcessor#filterPeak} Hz, matching the
	 * production filter envelope.</p>
	 *
	 * @param sources        per-layer flattened sources, each shape {@code [n, sourceLength]}
	 * @param ratios         per-layer per-note pitch ratios, each shape {@code [n]}
	 * @param layerEnvParams per-layer ADSR scalars for {@link #buildLayerEnvelopeCurve},
	 *                       {@code [layer][8]} = (mainDuration, f0, f1, f2, v0, v1, v2, v3), each {@code [n]}
	 * @param filterAdsr     filter-envelope scalars {@code [5]} = (attack, decay, sustain, release, duration), each {@code [n]}
	 * @param volumeAdsr     volume-envelope scalars {@code [5]} = (attack, decay, sustain, release, duration), each {@code [n]}
	 * @param destOffsets    per-note destination offsets in frames, shape {@code [n]}
	 * @param windowWidth    width of the output window in frames
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [windowWidth]}
	 */
	public CollectionProducer buildBatchedSssChainPlacedFromScalars(PackedCollection[] sources,
																	PackedCollection[] ratios,
																	PackedCollection[][] layerEnvParams,
																	PackedCollection[] filterAdsr,
																	PackedCollection[] volumeAdsr,
																	PackedCollection destOffsets,
																	int windowWidth) {
		return buildBatchedSssChainPlacedFromScalars(sources, ratios, layerEnvParams,
				filterAdsr, volumeAdsr, destOffsets, null, windowWidth);
	}

	/**
	 * Offset-aware form of
	 * {@link #buildBatchedSssChainPlacedFromScalars(PackedCollection[], PackedCollection[], PackedCollection[][], PackedCollection[], PackedCollection[], PackedCollection, int)}:
	 * each note's source read and every generated envelope (per-layer, filter
	 * cutoff, volume) are advanced by the note's per-note {@code samplingOffsets}, so
	 * a note that began in an earlier window renders the slice belonging to this
	 * window — reading from its within-note position and continuing its envelopes
	 * from the correct point — before being placed at its {@code destOffsets}. With
	 * {@code samplingOffsets} {@code null} this is identical to the sample-0 form.
	 *
	 * @param sources         per-layer flattened sources, each shape {@code [n, sourceLength]}
	 * @param ratios          per-layer per-note pitch ratios, each shape {@code [n]}
	 * @param layerEnvParams  per-layer ADSR scalars for {@link #buildLayerEnvelopeCurve}, {@code [layer][8]}
	 * @param filterAdsr      filter-envelope scalars {@code [5]}, each {@code [n]}
	 * @param volumeAdsr      volume-envelope scalars {@code [5]}, each {@code [n]}
	 * @param destOffsets     per-note destination offsets in frames, shape {@code [n]}
	 * @param samplingOffsets per-note within-note sampling offsets in frames, shape {@code [n]}, or {@code null}
	 * @param windowWidth     width of the output window in frames
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [windowWidth]}
	 */
	public CollectionProducer buildBatchedSssChainPlacedFromScalars(PackedCollection[] sources,
																	PackedCollection[] ratios,
																	PackedCollection[][] layerEnvParams,
																	PackedCollection[] filterAdsr,
																	PackedCollection[] volumeAdsr,
																	PackedCollection destOffsets,
																	PackedCollection samplingOffsets,
																	int windowWidth) {
		CollectionProducer[] layerEnv2D = new CollectionProducer[sources.length];
		for (int i = 0; i < sources.length; i++) {
			PackedCollection[] p = layerEnvParams[i];
			layerEnv2D[i] = buildLayerEnvelopeCurve(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], samplingOffsets);
		}

		CollectionProducer filterCutoffs2D = buildVolumeEnvelopeCurve(
				filterAdsr[0], filterAdsr[1], filterAdsr[2], filterAdsr[3], filterAdsr[4], samplingOffsets)
				.multiply(c(MultiOrderFilterEnvelopeProcessor.filterPeak));
		CollectionProducer volumeEnv2D = buildVolumeEnvelopeCurve(
				volumeAdsr[0], volumeAdsr[1], volumeAdsr[2], volumeAdsr[3], volumeAdsr[4], samplingOffsets);

		CollectionProducer voiced2D = voicedSss(sources, ratios, layerEnv2D,
				filterCutoffs2D, volumeEnv2D, samplingOffsets);
		return scatterAddFlat(voiced2D.reshape(shape(n * targetLength)),
				destOffsets, n, targetLength, windowWidth);
	}

	/**
	 * Generates the batched ADSR volume-envelope gain curves for all {@code n}
	 * notes from per-note scalar parameters, reproducing the shape of
	 * {@code EnvelopeFeatures.envelope(duration, attack, decay, sustain, release)}
	 * (used by {@code AudioProcessingUtils.getVolumeEnv}) so the curves are
	 * generated inside the batched kernel rather than materialized per note.
	 *
	 * <p>Attack is clamped to 75% and decay to 25% of the duration. For each note
	 * the gain at sample {@code i} (time {@code t = i / sampleRate}) is the
	 * piecewise-linear ADSR, selected with strict {@code >} comparisons to match
	 * {@code EnvelopeSection}:</p>
	 * <ul>
	 *   <li>{@code t <= a}: {@code min(t/a, 1)}</li>
	 *   <li>{@code a < t <= a+d}: {@code max(0, (1 - p) + sustain*p)}, {@code p = (t-a)/d}</li>
	 *   <li>{@code a+d < t <= duration}: {@code sustain}</li>
	 *   <li>{@code t > duration}: {@code max(0, sustain*(1 - (t-duration)/release))}</li>
	 * </ul>
	 *
	 * @param attack   per-note attack times in seconds, shape {@code [n]}
	 * @param decay    per-note decay times in seconds, shape {@code [n]}
	 * @param sustain  per-note sustain levels, shape {@code [n]}
	 * @param release  per-note release times in seconds, shape {@code [n]}
	 * @param duration per-note durations in seconds, shape {@code [n]}
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [n, targetLength]}
	 */
	public CollectionProducer buildVolumeEnvelopeCurve(PackedCollection attack,
													   PackedCollection decay,
													   PackedCollection sustain,
													   PackedCollection release,
													   PackedCollection duration) {
		return buildVolumeEnvelopeCurve(attack, decay, sustain, release, duration, null);
	}

	/**
	 * Offset-aware form of
	 * {@link #buildVolumeEnvelopeCurve(PackedCollection, PackedCollection, PackedCollection, PackedCollection, PackedCollection)}:
	 * evaluates each note's gain at note-elapsed time {@code (samplingOffset + i) / sampleRate}
	 * rather than {@code i / sampleRate}, so a note that began in an earlier window
	 * continues its ADSR from the correct point. With {@code samplingOffsets}
	 * {@code null} the behavior is identical to the sample-0 form.
	 *
	 * @param attack          per-note attack times in seconds, shape {@code [n]}
	 * @param decay           per-note decay times in seconds, shape {@code [n]}
	 * @param sustain         per-note sustain levels, shape {@code [n]}
	 * @param release         per-note release times in seconds, shape {@code [n]}
	 * @param duration        per-note durations in seconds, shape {@code [n]}
	 * @param samplingOffsets per-note sampling offsets in frames, shape {@code [n]}, or {@code null}
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [n, targetLength]}
	 */
	public CollectionProducer buildVolumeEnvelopeCurve(PackedCollection attack,
													   PackedCollection decay,
													   PackedCollection sustain,
													   PackedCollection release,
													   PackedCollection duration,
													   PackedCollection samplingOffsets) {
		int total = n * targetLength;

		CollectionProducer t = integers(0, total);
		CollectionProducer noteIdx = floor(t.divide(c((double) targetLength)));
		CollectionProducer sampleIdx = effectiveSampleIdx(
				t.subtract(noteIdx.multiply(c((double) targetLength))), noteIdx, samplingOffsets, total);
		CollectionProducer time = sampleIdx.divide(c((double) sampleRate));

		CollectionProducer att = c(shape(total), cp(attack), noteIdx);
		CollectionProducer dec = c(shape(total), cp(decay), noteIdx);
		CollectionProducer sus = c(shape(total), cp(sustain), noteIdx);
		CollectionProducer rel = c(shape(total), cp(release), noteIdx);
		CollectionProducer dur = c(shape(total), cp(duration), noteIdx);

		// Clamp attack to 75% and decay to 25% of the duration.
		CollectionProducer a = min(att, dur.multiply(c(0.75)));
		CollectionProducer d = min(dec, dur.multiply(c(0.25)));

		CollectionProducer attackGain = min(time.divide(a), c(1.0));
		CollectionProducer decayGain = linearSegment(time, a, d, c(1.0), sus);
		CollectionProducer releaseGain = linearSegment(time, dur, rel, sus, c(0.0));

		// Piecewise selection (strict >): t>dur ? release : t>a+d ? sustain : t>a ? decay : attack
		CollectionProducer afterAttack = time.greaterThan(a, decayGain, attackGain);
		CollectionProducer afterDecay = time.greaterThan(a.add(d), sus, afterAttack);
		CollectionProducer gain = time.greaterThan(dur, releaseGain, afterDecay);

		return gain.reshape(shape(n, targetLength));
	}

	/**
	 * Generates the batched per-layer envelope gain curves for all {@code n}
	 * notes, reproducing {@code AudioProcessingUtils.getLayerEnv} (used by
	 * {@code ParameterizedLayerEnvelope}). The curve is a three-segment
	 * piecewise-linear envelope through the points {@code (0, v0)},
	 * {@code (d0, v1)}, {@code (d1, v2)}, {@code (d2, v3)}, where
	 * {@code dk = mainDuration * fk}, selected with strict {@code >} comparisons:
	 * {@code t>d1 ? seg2 : t>d0 ? seg1 : seg0}.
	 *
	 * @param mainDuration per-note base duration in seconds, shape {@code [n]}
	 * @param f0 per-note segment-0 end fraction of {@code mainDuration}, shape {@code [n]}
	 * @param f1 per-note segment-1 end fraction of {@code mainDuration}, shape {@code [n]}
	 * @param f2 per-note segment-2 end fraction of {@code mainDuration}, shape {@code [n]}
	 * @param v0 per-note level at time 0, shape {@code [n]}
	 * @param v1 per-note level at {@code d0}, shape {@code [n]}
	 * @param v2 per-note level at {@code d1}, shape {@code [n]}
	 * @param v3 per-note level at {@code d2}, shape {@code [n]}
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [n, targetLength]}
	 */
	public CollectionProducer buildLayerEnvelopeCurve(PackedCollection mainDuration,
													  PackedCollection f0, PackedCollection f1, PackedCollection f2,
													  PackedCollection v0, PackedCollection v1,
													  PackedCollection v2, PackedCollection v3) {
		return buildLayerEnvelopeCurve(mainDuration, f0, f1, f2, v0, v1, v2, v3, null);
	}

	/**
	 * Offset-aware form of
	 * {@link #buildLayerEnvelopeCurve(PackedCollection, PackedCollection, PackedCollection, PackedCollection, PackedCollection, PackedCollection, PackedCollection, PackedCollection)}:
	 * evaluates each note's layer envelope at note-elapsed time
	 * {@code (samplingOffset + i) / sampleRate} so a note continuing from an earlier
	 * window resumes at the correct point. With {@code samplingOffsets} {@code null}
	 * the behavior is identical to the sample-0 form.
	 *
	 * @param mainDuration    per-note base duration in seconds, shape {@code [n]}
	 * @param f0 per-note segment-0 end fraction of {@code mainDuration}, shape {@code [n]}
	 * @param f1 per-note segment-1 end fraction of {@code mainDuration}, shape {@code [n]}
	 * @param f2 per-note segment-2 end fraction of {@code mainDuration}, shape {@code [n]}
	 * @param v0 per-note level at time 0, shape {@code [n]}
	 * @param v1 per-note level at {@code d0}, shape {@code [n]}
	 * @param v2 per-note level at {@code d1}, shape {@code [n]}
	 * @param v3 per-note level at {@code d2}, shape {@code [n]}
	 * @param samplingOffsets per-note sampling offsets in frames, shape {@code [n]}, or {@code null}
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [n, targetLength]}
	 */
	public CollectionProducer buildLayerEnvelopeCurve(PackedCollection mainDuration,
													  PackedCollection f0, PackedCollection f1, PackedCollection f2,
													  PackedCollection v0, PackedCollection v1,
													  PackedCollection v2, PackedCollection v3,
													  PackedCollection samplingOffsets) {
		int total = n * targetLength;

		CollectionProducer t = integers(0, total);
		CollectionProducer noteIdx = floor(t.divide(c((double) targetLength)));
		CollectionProducer sampleIdx = effectiveSampleIdx(
				t.subtract(noteIdx.multiply(c((double) targetLength))), noteIdx, samplingOffsets, total);
		CollectionProducer time = sampleIdx.divide(c((double) sampleRate));

		CollectionProducer dr = c(shape(total), cp(mainDuration), noteIdx);
		CollectionProducer fr0 = c(shape(total), cp(f0), noteIdx);
		CollectionProducer fr1 = c(shape(total), cp(f1), noteIdx);
		CollectionProducer fr2 = c(shape(total), cp(f2), noteIdx);
		CollectionProducer vol0 = c(shape(total), cp(v0), noteIdx);
		CollectionProducer vol1 = c(shape(total), cp(v1), noteIdx);
		CollectionProducer vol2 = c(shape(total), cp(v2), noteIdx);
		CollectionProducer vol3 = c(shape(total), cp(v3), noteIdx);

		CollectionProducer d0 = dr.multiply(fr0);
		CollectionProducer d1 = dr.multiply(fr1);
		CollectionProducer d2 = dr.multiply(fr2);

		CollectionProducer seg0 = linearSegment(time, c(0.0), d0, vol0, vol1);
		CollectionProducer seg1 = linearSegment(time, d0, d1.subtract(d0), vol1, vol2);
		CollectionProducer seg2 = linearSegment(time, d1, d2.subtract(d1), vol2, vol3);

		CollectionProducer afterFirst = time.greaterThan(d0, seg1, seg0);
		CollectionProducer gain = time.greaterThan(d1, seg2, afterFirst);

		return gain.reshape(shape(n, targetLength));
	}

	/**
	 * One clamped linear envelope segment, matching
	 * {@code EnvelopeFeatures.linear(offset, duration, startVolume, endVolume)}:
	 * {@code max(0, (1 - p)*startVolume + p*endVolume)} with
	 * {@code p = (time - offset) / duration}. All inputs are flat producers of
	 * the same length (or scalars to broadcast).
	 *
	 * @param time     per-sample time in seconds
	 * @param offset   segment start time in seconds
	 * @param duration segment duration in seconds
	 * @param startV   level at the segment start
	 * @param endV     level at the segment end
	 * @return an uncompiled {@link CollectionProducer} of the segment level
	 */
	private CollectionProducer linearSegment(CollectionProducer time, CollectionProducer offset,
											 CollectionProducer duration, CollectionProducer startV,
											 CollectionProducer endV) {
		CollectionProducer pos = time.subtract(offset).divide(duration);
		CollectionProducer oneMinusPos = pos.multiply(c(-1.0)).add(c(1.0));
		return max(oneMinusPos.multiply(startV).add(pos.multiply(endV)), c(0.0));
	}

	/**
	 * Adds the per-note sampling offset to the within-row sample index so a note
	 * that began in an earlier window is read (and enveloped) from its current
	 * within-note position rather than from sample 0. With {@code samplingOffsets}
	 * {@code null} the index is returned unchanged (a sample-0 origin), preserving
	 * the single-window behavior of every existing caller.
	 *
	 * @param sampleIdx       within-row sample index, flat shape {@code [total]}
	 * @param noteIdx         per-sample note index, flat shape {@code [total]}
	 * @param samplingOffsets per-note sampling offsets in frames, shape {@code [n]}, or {@code null}
	 * @param total           flat length {@code n * targetLength}
	 * @return the effective within-note sample index, flat shape {@code [total]}
	 */
	private CollectionProducer effectiveSampleIdx(CollectionProducer sampleIdx, CollectionProducer noteIdx,
												  PackedCollection samplingOffsets, int total) {
		if (samplingOffsets == null) return sampleIdx;
		return sampleIdx.add(c(shape(total), cp(samplingOffsets), noteIdx));
	}

	/**
	 * Kernel 1 — batched linear resample for one source layer. Maps each flat
	 * output index to {@code (noteIdx, sampleIdx)} and gathers from the note's
	 * source row with linear interpolation, using the per-note {@code ratios}.
	 *
	 * @param source          flattened source audio, shape {@code [n, sourceLength]}
	 * @param ratios          per-note pitch ratios, shape {@code [n]}
	 * @param samplingOffsets per-note sampling offsets in frames, shape {@code [n]}, or
	 *                        {@code null} to read every note from sample 0
	 * @return an uncompiled {@link CollectionProducer} of flat shape {@code [n * targetLength]}
	 */
	private CollectionProducer resampleFlat(PackedCollection source, PackedCollection ratios,
											PackedCollection samplingOffsets) {
		int totalSamples = n * targetLength;

		CollectionProducer outIdx = integers(0, totalSamples);
		// noteIdx = floor(outIdx / targetLength)
		CollectionProducer noteIdx = floor(outIdx.multiply(c(1.0 / targetLength)));
		// sampleIdx = outIdx - noteIdx * targetLength, shifted by the note's sampling offset
		CollectionProducer sampleIdx = effectiveSampleIdx(
				outIdx.subtract(noteIdx.multiply(c((double) targetLength))), noteIdx, samplingOffsets, totalSamples);
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
	 * @param filterCutoffs2D per-note per-sample cutoff envelopes (producer), shape {@code [n, targetLength]}
	 * @param volumeEnv2D     per-note per-sample volume gain envelopes (producer), shape {@code [n, targetLength]}
	 * @return an uncompiled {@link CollectionProducer} of shape {@code [n, targetLength]}
	 */
	private CollectionProducer filterVolume2D(CollectionProducer audio2D,
											  CollectionProducer filterCutoffs2D,
											  CollectionProducer volumeEnv2D) {
		int totalSamples = n * targetLength;
		int paddedNoteSize = targetLength + 2 * padHalf;
		int paddedTotal = n * paddedNoteSize;

		CollectionProducer paddedAudio2D = pad(audio2D, 0, padHalf);
		CollectionProducer paddedCutoff2D = pad(filterCutoffs2D, 0, padHalf);
		CollectionProducer flatPaddedAudio = paddedAudio2D.reshape(shape(paddedTotal));
		CollectionProducer flatPaddedCutoff = paddedCutoff2D.reshape(shape(paddedTotal));
		CollectionProducer filtered =
				c(lowPass(traverseEach(flatPaddedAudio), flatPaddedCutoff, sampleRate, filterOrder));
		CollectionProducer filtered2D = filtered.reshape(shape(n, paddedNoteSize));
		CollectionProducer trimmed = subset(shape(n, targetLength), filtered2D, 0, padHalf);

		CollectionProducer flatTrimmed = trimmed.reshape(shape(totalSamples));
		CollectionProducer flatVolumeEnv = volumeEnv2D.reshape(shape(totalSamples));
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
