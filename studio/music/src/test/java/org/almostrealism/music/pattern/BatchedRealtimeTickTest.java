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

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;

/**
 * Renders a melodic pattern the way the real-time path does — one
 * {@code bufferSize} window per tick, advancing through the pattern — through both
 * the per-note path and the batched dispatch, and verifies the two agree across
 * every tick boundary (so note continuation across ticks, driven by the per-note
 * sampling offset, is correct at production cadence). Per-tick wall-clock for each
 * path is logged as the performance signal (the point of the batched path is to
 * replace per-note JNI dispatch with one kernel per tick); only the audio
 * agreement is asserted, since timing is hardware-dependent.
 */
public class BatchedRealtimeTickTest extends TestSuiteBase implements AudioTestFeatures {

	/** Measure duration in seconds at 120 BPM. */
	private static final double MEASURE_DURATION = 2.0;

	/** Per-tick render window in frames (a typical audio buffer). */
	private static final int BUFFER = 4096;

	/** Number of consecutive ticks rendered. */
	private static final int TICKS = 3;

	/** Creates a {@link NoteAudioChoice} with three octave-spanning file sources for melodic rendering. */
	private NoteAudioChoice melodicChoice(DefaultKeyboardTuning tuning) {
		NoteAudioChoice choice = NoteAudioChoice.fromSource("Harmony",
				new FileNoteSource(getNamedTestWavPath("rt_c0.wav", 27.5, 2.0, false),
						WesternChromatic.A0),
				0, 9, true);
		choice.getSources().add(new FileNoteSource(
				getNamedTestWavPath("rt_c1.wav", 32.7, 2.0, false), WesternChromatic.C1));
		choice.getSources().add(new FileNoteSource(
				getNamedTestWavPath("rt_c2.wav", 65.4, 2.0, false), WesternChromatic.C2));
		choice.setTuning(tuning);
		choice.setBias(1.0);
		return choice;
	}

	/** Builds an {@link AudioSceneContext} configured for the given measure count and frame-per-measure rate. */
	private AudioSceneContext context(int measures, double measureFrames) {
		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(measures);
		context.setFrames(BUFFER);
		context.setFrameForPosition(pos -> (int) (pos * measureFrames));
		context.setTimeForDuration(pos -> pos * MEASURE_DURATION);
		context.setScaleForPosition(pos -> WesternScales.major(WesternChromatic.C4, 1));
		context.setChannels(List.of(
				new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT)));
		return context;
	}

	/** Renders one tick into a fresh buffer copied into {@code full} at {@code startFrame}; returns nanos. */
	private long renderTick(PatternLayerManager manager, AudioSceneContext ctx, PackedCollection full,
							int startFrame, boolean batched) {
		PackedCollection dest = new PackedCollection(BUFFER);
		ctx.setDestination(dest);
		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = batched;
		long elapsed;
		try {
			manager.updateDestination(ctx);
			long t0 = System.nanoTime();
			manager.sum(() -> ctx, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
					() -> startFrame, BUFFER).get().run();
			elapsed = System.nanoTime() - t0;
		} finally {
			PatternLayerManager.enableBatched = previous;
		}
		full.setMem(startFrame, dest, 0, BUFFER);
		return elapsed;
	}

	/** Renders all {@link #TICKS} ticks into {@code full}; returns total nanos. */
	private long renderAllTicks(PatternLayerManager manager, AudioSceneContext ctx,
								PackedCollection full, boolean batched) {
		long total = 0;
		for (int t = 0; t < TICKS; t++) {
			total += renderTick(manager, ctx, full, t * BUFFER, batched);
		}
		return total;
	}

	/**
	 * Verifies that the batched tick-by-tick renderer produces audio that agrees with
	 * the per-note renderer within 5% relative RMS across all rendered ticks.
	 */
	@Test(timeout = 180000)
	@TestDepth(2)
	public void batchedTickByTickMatchesPerNote() {
		DefaultKeyboardTuning tuning = new DefaultKeyboardTuning();
		List<NoteAudioChoice> choices = List.of(melodicChoice(tuning));
		double measureFrames = MEASURE_DURATION * OutputLine.sampleRate;
		int measures = 2;
		int span = TICKS * BUFFER;

		// Retry genomes until the batched path dispatches every tick (no fallback).
		PatternLayerManager manager = null;
		for (int attempt = 0; attempt < 60 && manager == null; attempt++) {
			PatternLayerManager candidate = new PatternLayerManager(
					choices, new ProjectedGenome(8).addChromosome(), 0, measures, true);
			candidate.setScaleTraversalDepth(3);
			candidate.setLayerCount(3);
			if (candidate.getAllElements(0, measures).isEmpty()) continue;

			PackedCollection probe = new PackedCollection(span);
			BatchedPatternLayerRenderer.resetCounters();
			renderAllTicks(candidate, context(measures, measureFrames), probe, true);
			if (BatchedPatternLayerRenderer.batchedDispatchCount.get() > 0
					&& BatchedPatternLayerRenderer.fallbackCount.get() == 0) {
				manager = candidate;
			}
		}
		Assume.assumeTrue("no genome dispatched every batched tick", manager != null);

		// Warm up both paths (compile kernels / populate the per-note cache), then time.
		PackedCollection warm = new PackedCollection(span);
		renderAllTicks(manager, context(measures, measureFrames), warm, false);
		renderAllTicks(manager, context(measures, measureFrames), warm, true);

		PackedCollection perNote = new PackedCollection(span);
		long perNoteNanos = renderAllTicks(manager, context(measures, measureFrames), perNote, false);

		PackedCollection batched = new PackedCollection(span);
		BatchedPatternLayerRenderer.resetCounters();
		long batchedNanos = renderAllTicks(manager, context(measures, measureFrames), batched, true);
		double marshalMs = BatchedPatternLayerRenderer.marshalNanos.get() / 1_000_000.0;
		double evalMs = BatchedPatternLayerRenderer.evalNanos.get() / 1_000_000.0;
		double gatherMs = BatchedPatternLayerRenderer.gatherNanos.get() / 1_000_000.0;

		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		for (int i = 0; i < span; i++) {
			double ref = perNote.toDouble(i);
			double diff = ref - batched.toDouble(i);
			sumSqDiff += diff * diff;
			sumSqRef += ref * ref;
		}
		double rms = Math.sqrt(sumSqDiff / span);
		double refRms = Math.sqrt(sumSqRef / span);
		double relative = refRms > 1e-10 ? rms / refRms : 0.0;

		log("realtime ticks=" + TICKS + " buffer=" + BUFFER
				+ ": perNote=" + (perNoteNanos / 1_000_000.0) + "ms batched="
				+ (batchedNanos / 1_000_000.0) + "ms perTickBatched="
				+ (batchedNanos / 1_000_000.0 / TICKS) + "ms");
		log("batched split: gather=" + gatherMs + "ms marshal=" + marshalMs + "ms eval=" + evalMs
				+ "ms (over " + TICKS + " ticks)");
		log("realtime equivalence: refRms=" + refRms + " diffRms=" + rms + " relative=" + relative);

		Assert.assertTrue("per-note render is trivially silent (refRms=" + refRms + ")",
				refRms > 1e-4);
		Assert.assertTrue("batched tick-by-tick diverges from per-note (relative RMS " + relative + ")",
				relative < 0.05);
	}
}
