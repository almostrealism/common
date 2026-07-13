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

import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudio;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedLayerEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudio;
import org.almostrealism.music.notes.PatternNoteAudioChoice;
import org.almostrealism.music.notes.PatternNoteLayer;
import org.almostrealism.music.notes.SimplePatternNote;

import java.util.List;
import java.util.function.DoubleFunction;

/**
 * The flat per-note input record for the batched melodic-SSS renderer
 * ({@code BatchedPatternRenderer.buildBatchedSssChainPlacedFromScalars}),
 * extracted from a production {@link PatternNote} without evaluating the note's
 * producer graph.
 *
 * <p>The production melodic note is a three-level structure: an outer
 * delegate-mode {@link PatternNote} carrying a {@link ParameterizedVolumeEnvelope}
 * filter, whose delegate is a delegate-mode {@link PatternNote} carrying a
 * {@link ParameterizedFilterEnvelope} filter, whose delegate is a layer-mode
 * {@link PatternNote} with three {@link PatternNoteLayer}s (each a sample choice
 * plus a {@link ParameterizedLayerEnvelope} filter), summed by the
 * {@code SOURCE, SOURCE, SOURCE} aggregation (aggregation choice 0). This class
 * peels that structure and reads the per-note ADSR scalars (replicating the
 * automation adjustment and clamps the envelope filters apply at render time),
 * the per-layer raw source buffers, and the pitch ratios.</p>
 *
 * <p>{@link #from} returns {@code null} for any note that is not the melodic-SSS
 * shape, so callers fall back to per-note rendering for unsupported notes.</p>
 */
public class BatchedNoteInputs {

	/** Number of source layers in the melodic-SSS shape. */
	public static final int LAYERS = 3;

	/** Per-layer raw source buffers (full resampled length, or fitted when requested). */
	private final PackedCollection[] sources;

	/** Per-layer pitch ratios. */
	private final double[] ratios;

	/** Per-layer envelope scalars, {@code [LAYERS][8]}. */
	private final double[][] layerParams;

	/** Filter-envelope scalars {@code [5]}; {@code null} for percussion. */
	private final double[] filterAdsr;

	/** Volume-envelope scalars {@code [5]}; {@code null} for a dry percussion note. */
	private final double[] volumeAdsr;

	/**
	 * Whether this record is the full melodic-SSS shape (per-layer envelopes, filter
	 * envelope, pitched resample); {@code false} for the percussion subset (no per-layer
	 * envelope, no filter envelope, unity pitch), matching the {@code melodic} flag used
	 * throughout the pattern system.
	 */
	private final boolean melodic;

	/** Whether this note carries a volume envelope on the wet voicing (always {@code true} for melodic). */
	private final boolean wet;

	/**
	 * Creates an input record from the extracted per-note tensors.
	 *
	 * @param sources     per-layer raw source buffers
	 * @param ratios      per-layer pitch ratios
	 * @param layerParams per-layer envelope scalars, or {@code null} for percussion
	 * @param filterAdsr  filter-envelope scalars, or {@code null} for percussion
	 * @param volumeAdsr  volume-envelope scalars, or {@code null} for a dry percussion note
	 * @param melodic     whether this is the full melodic-SSS shape (percussion otherwise)
	 * @param wet         whether this note carries a volume envelope (the wet voicing)
	 */
	private BatchedNoteInputs(PackedCollection[] sources, double[] ratios, double[][] layerParams,
							  double[] filterAdsr, double[] volumeAdsr, boolean melodic, boolean wet) {
		this.sources = sources;
		this.ratios = ratios;
		this.layerParams = layerParams;
		this.filterAdsr = filterAdsr;
		this.volumeAdsr = volumeAdsr;
		this.melodic = melodic;
		this.wet = wet;
	}

	/** Per-layer raw source buffers (full resampled length, or fitted when requested). */
	public PackedCollection[] getSources() { return sources; }

	/** Per-layer pitch ratios (target frequency / sample-root frequency). */
	public double[] getRatios() { return ratios; }

	/** Per-layer envelope scalars, {@code [LAYERS][8]} = (mainDuration, f0, f1, f2, v0, v1, v2, v3). */
	public double[][] getLayerParams() { return layerParams; }

	/** Filter-envelope scalars {@code [5]} = (attack, decay, sustain, release, duration). */
	public double[] getFilterAdsr() { return filterAdsr; }

	/** Volume-envelope scalars {@code [5]} = (attack, decay, sustain, release, duration); {@code null} when dry. */
	public double[] getVolumeAdsr() { return volumeAdsr; }

	/**
	 * Returns whether this is the full melodic-SSS shape; {@code false} for the
	 * percussion subset (no per-layer envelope, no filter envelope, unity pitch).
	 */
	public boolean isMelodic() { return melodic; }

	/** Returns whether this note carries a volume envelope (the wet voicing). */
	public boolean isWet() { return wet; }

	/**
	 * Returns whether the given note is the production melodic three-layer SSS
	 * shape: outer delegate-mode wrapping a delegate-mode wrapping a layer-mode
	 * note with three layers and aggregation choice 0.
	 *
	 * @param outer the note to classify
	 * @return {@code true} if the note matches the melodic-SSS shape
	 */
	public static boolean isMelodicSssShape(PatternNote outer) {
		if (!(outer.getDelegate() instanceof PatternNote mid)) return false;
		if (!(mid.getDelegate() instanceof PatternNote inner)) return false;
		List<PatternNoteAudio> layers = inner.getLayers();
		return layers != null && layers.size() == LAYERS
				&& inner.getAggregationChoice() == 0.0
				&& layers.stream().allMatch(l -> l instanceof PatternNoteLayer);
	}

	/**
	 * Extracts the flat input record from a production melodic note, or returns
	 * {@code null} if the note is not the melodic-SSS shape (or a layer source
	 * cannot be resolved to a {@link NoteAudioProvider}).
	 *
	 * @param outer           the outer (volume-envelope) note
	 * @param target          the target key position for pitch shifting, or {@code null} for unity ratio
	 * @param channel         the audio channel index
	 * @param durationSec     the note duration in seconds
	 * @param automationLevel the automation level driving the envelope adjustment
	 * @param audioSelection  the function resolving a layer choice to concrete audio
	 * @return the extracted record, or {@code null} if unsupported
	 */
	public static BatchedNoteInputs from(PatternNote outer, KeyPosition<?> target, int channel,
										 double durationSec, double automationLevel,
										 DoubleFunction<PatternNoteAudio> audioSelection) {
		return from(outer, target, channel, durationSec, automationLevel, audioSelection, 0);
	}

	/**
	 * Extracts the flat input record from a production melodic note, fitting each
	 * layer's source to a fixed length, or returns {@code null} if the note is not
	 * the melodic-SSS shape. A {@code sourceLength} of {@code 0} (or less) keeps the
	 * full resampled source — the form the production dispatch uses, since the
	 * window it must render is not known at gather time.
	 *
	 * @param outer           the outer (volume-envelope) note
	 * @param target          the target key position for pitch shifting, or {@code null} for unity ratio
	 * @param channel         the audio channel index
	 * @param durationSec     the note duration in seconds
	 * @param automationLevel the automation level driving the envelope adjustment
	 * @param audioSelection  the function resolving a layer choice to concrete audio
	 * @param sourceLength    the per-layer source buffer length, or {@code 0} to keep the full source
	 * @return the extracted record, or {@code null} if unsupported
	 */
	public static BatchedNoteInputs from(PatternNote outer, KeyPosition<?> target, int channel,
										 double durationSec, double automationLevel,
										 DoubleFunction<PatternNoteAudio> audioSelection, int sourceLength) {
		if (!(outer.getFilter() instanceof ParameterizedVolumeEnvelope.Filter volF)) return null;
		if (!(outer.getDelegate() instanceof PatternNote mid)) return null;
		if (!(mid.getFilter() instanceof ParameterizedFilterEnvelope.Filter filtF)) return null;
		if (!(mid.getDelegate() instanceof PatternNote inner)) return null;

		List<PatternNoteAudio> layers = inner.getLayers();
		if (layers == null || layers.size() != LAYERS || inner.getAggregationChoice() != 0.0) {
			return null;
		}

		// Volume envelope scalars: getVolumeEnv adjusts and clamps sustain/release.
		double adjV = ParameterizedVolumeEnvelope.adjustmentBase
				+ ParameterizedVolumeEnvelope.adjustmentAutomation * automationLevel;
		double vSus = clamp(volF.getSustain() * adjV, 0.25, 1.0);
		double vRel = Math.min(volF.getRelease(durationSec) * adjV, 0.7 * durationSec);
		double[] volumeAdsr = { volF.getAttack(durationSec), volF.getDecay(), vSus, vRel, durationSec };

		// Filter envelope scalars: cutoff = ADSR shape; sustain/release scaled by adjustment, no clamp.
		double adjF = ParameterizedFilterEnvelope.adjustmentBase
				+ ParameterizedFilterEnvelope.adjustmentAutomation * automationLevel;
		double[] filterAdsr = { filtF.getAttack(), filtF.getDecay(),
				filtF.getSustain() * adjF, filtF.getRelease() * adjF, durationSec };

		PackedCollection[] sources = new PackedCollection[LAYERS];
		double[] ratios = new double[LAYERS];
		double[][] layerParams = new double[LAYERS][];
		for (int i = 0; i < LAYERS; i++) {
			if (!(layers.get(i) instanceof PatternNoteLayer layer)) return null;
			if (!(layer.getFilter() instanceof ParameterizedLayerEnvelope.Filter lf)) return null;
			if (!resolveSourceAndRatio(layer.getDelegate(), target, channel, sourceLength,
					audioSelection, sources, ratios, i)) {
				return null;
			}
			layerParams[i] = new double[] { durationSec,
					lf.getAttack(), lf.getSustain(), lf.getRelease(),
					lf.getVolume0(), lf.getVolume1(), lf.getVolume2(), lf.getVolume3() };
		}

		return new BatchedNoteInputs(sources, ratios, layerParams, filterAdsr, volumeAdsr, true, true);
	}

	/**
	 * Returns whether the given note is the production percussion three-layer SSS
	 * shape: a layer-mode note with three bare {@link PatternNoteAudioChoice} layers
	 * and aggregation choice 0, optionally wrapped in a single delegate-mode note
	 * carrying a {@link ParameterizedVolumeEnvelope.Filter} (the wet voicing). A
	 * percussion note is the strict subset of the melodic shape: no per-layer
	 * envelope and no filter envelope, so the (peeled) inner note must have a
	 * {@code null} filter.
	 *
	 * @param outer the note to classify
	 * @return {@code true} if the note matches the percussion-SSS shape
	 */
	public static boolean isPercussionSssShape(PatternNote outer) {
		PatternNote inner = outer;
		if (outer.getFilter() instanceof ParameterizedVolumeEnvelope.Filter) {
			if (!(outer.getDelegate() instanceof PatternNote mid)) return false;
			inner = mid;
		}
		if (inner.getFilter() != null) return false;
		List<PatternNoteAudio> layers = inner.getLayers();
		return layers != null && layers.size() == LAYERS
				&& inner.getAggregationChoice() == 0.0
				&& layers.stream().allMatch(l -> l instanceof PatternNoteAudioChoice);
	}

	/**
	 * Extracts the flat input record from a production percussion note, or returns
	 * {@code null} if the note is not the percussion-SSS shape (or a layer source
	 * cannot be resolved to a {@link NoteAudioProvider}). A percussion note is a
	 * strict subset of the melodic note: the same three-source SSS sum, but at unity
	 * pitch (sample-rate-only resample), with no per-layer envelope and no filter
	 * envelope, and a volume envelope only on the wet voicing — so {@code layerParams}
	 * and {@code filterAdsr} are {@code null}, and {@code volumeAdsr} is non-{@code null}
	 * only when the outer note carries a {@link ParameterizedVolumeEnvelope.Filter}.
	 *
	 * @param outer           the outer note (volume-enveloped for wet, or bare layer-mode for dry)
	 * @param channel         the audio channel index
	 * @param durationSec     the note duration in seconds
	 * @param automationLevel the automation level driving the volume-envelope adjustment
	 * @param audioSelection  the function resolving a layer choice to concrete audio
	 * @return the extracted record, or {@code null} if unsupported
	 */
	public static BatchedNoteInputs fromPercussion(PatternNote outer, int channel,
												   double durationSec, double automationLevel,
												   DoubleFunction<PatternNoteAudio> audioSelection) {
		boolean wet = outer.getFilter() instanceof ParameterizedVolumeEnvelope.Filter;
		PatternNote inner = wet ? (PatternNote) outer.getDelegate() : outer;

		List<PatternNoteAudio> layers = inner.getLayers();
		if (layers == null || layers.size() != LAYERS || inner.getAggregationChoice() != 0.0) {
			return null;
		}

		// Volume envelope is present only on the wet voicing; the dry (MAIN) voicing
		// has none. When wet, the scalars are adjusted and clamped exactly as the
		// melodic from() path (getVolumeEnv applies the same automation and clamps).
		double[] volumeAdsr = null;
		if (wet) {
			ParameterizedVolumeEnvelope.Filter volF = (ParameterizedVolumeEnvelope.Filter) outer.getFilter();
			double adjV = ParameterizedVolumeEnvelope.adjustmentBase
					+ ParameterizedVolumeEnvelope.adjustmentAutomation * automationLevel;
			double vSus = clamp(volF.getSustain() * adjV, 0.25, 1.0);
			double vRel = Math.min(volF.getRelease(durationSec) * adjV, 0.7 * durationSec);
			volumeAdsr = new double[] { volF.getAttack(durationSec), volF.getDecay(), vSus, vRel, durationSec };
		}

		PackedCollection[] sources = new PackedCollection[LAYERS];
		double[] ratios = new double[LAYERS];
		for (int i = 0; i < LAYERS; i++) {
			// Percussion layers are bare PatternNoteAudioChoice (no PatternNoteLayer
			// wrapper, no per-layer envelope). Resolve at unity pitch (target == null
			// => ratio 1.0, sample-rate-only fold), keeping the full raw source.
			if (!resolveSourceAndRatio(layers.get(i), null, channel, 0,
					audioSelection, sources, ratios, i)) {
				return null;
			}
		}

		return new BatchedNoteInputs(sources, ratios, null, null, volumeAdsr, false, wet);
	}

	/**
	 * Resolves one layer's raw source buffer and pitch ratio into {@code sources[i]}
	 * and {@code ratios[i]}, shared by the melodic and percussion extraction paths.
	 *
	 * <p>Fetches the RAW (un-resampled) channel — a cached lookup shared by every note
	 * using this sample — and lets the batched kernel do the resample from the per-note
	 * ratio. This avoids the per-note interpolation that {@code getChannelData(rate)}
	 * runs, which dominates cost when pre-resampling here. The effective ratio folds in
	 * any sample-rate conversion, matching the production
	 * {@code getChannelData(channel, rate, sampleRate)} path. (The kernel's linear
	 * resample matches production for ratio &gt;= 1 and diverges slightly for pitch-down;
	 * see ResampleEquivalenceTest.) With {@code target == null} the pitch ratio is unity,
	 * so only the sample-rate fold remains — the percussion case.</p>
	 *
	 * @param start          the layer's audio entry point (a {@link PatternNoteAudioChoice}
	 *                       directly, or a {@link PatternNoteLayer}'s delegate)
	 * @param target         the target key position for pitch shifting, or {@code null} for unity ratio
	 * @param channel        the audio channel index
	 * @param sourceLength   the per-layer source buffer length, or {@code 0} to keep the full source
	 * @param audioSelection the function resolving a layer choice to concrete audio
	 * @param sources        the per-layer source buffers to populate
	 * @param ratios         the per-layer pitch ratios to populate
	 * @param i              the layer index being resolved
	 * @return {@code true} if the layer resolved to a {@link NoteAudioProvider}, {@code false} otherwise
	 */
	private static boolean resolveSourceAndRatio(PatternNoteAudio start, KeyPosition<?> target,
												 int channel, int sourceLength,
												 DoubleFunction<PatternNoteAudio> audioSelection,
												 PackedCollection[] sources, double[] ratios, int i) {
		PatternNoteAudio resolved = start;
		if (resolved instanceof PatternNoteAudioChoice choice) {
			resolved = choice.getDelegate(audioSelection);
		}
		if (!(resolved instanceof SimplePatternNote simple)) return false;
		NoteAudio na = simple.getNoteAudio();
		if (!(na instanceof NoteAudioProvider provider)) return false;

		WaveDataProvider wave = provider.getProvider();
		double effectiveRatio = pitchRatio(provider, target);
		if (wave.getSampleRate() != OutputLine.sampleRate) {
			effectiveRatio = effectiveRatio * wave.getSampleRate() / (double) OutputLine.sampleRate;
		}
		PackedCollection raw = wave.getChannelData(channel, 1.0);
		sources[i] = sourceLength > 0 ? fit(raw, sourceLength) : raw;
		ratios[i] = effectiveRatio;
		return true;
	}

	/** Computes the resample ratio target/root in Hz, or 1.0 when no target or tuning is available. */
	private static double pitchRatio(NoteAudioProvider provider, KeyPosition<?> target) {
		KeyboardTuning tuning = provider.getTuning();
		KeyPosition<?> root = provider.getRoot();
		if (target == null || tuning == null || root == null) return 1.0;
		return tuning.getTone(target).asHertz() / tuning.getTone(root).asHertz();
	}

	/**
	 * Clamps {@code v} to the closed interval {@code [lo, hi]}.
	 *
	 * @param v  the value to clamp
	 * @param lo the lower bound
	 * @param hi the upper bound
	 * @return the clamped value
	 */
	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : Math.min(v, hi);
	}

	/** Copies the first {@code length} samples of {@code raw} into a fresh buffer (zero-padded). */
	private static PackedCollection fit(PackedCollection raw, int length) {
		PackedCollection out = new PackedCollection(length);
		int copy = Math.min(raw.getMemLength(), length);
		out.setFrom(0, raw, 0, copy);
		return out;
	}
}
