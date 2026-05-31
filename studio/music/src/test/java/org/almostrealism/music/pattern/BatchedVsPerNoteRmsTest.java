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
 * End-to-end equivalence check: renders the same melodic pattern window once
 * through the production per-note path ({@code renderPerNote}, evaluating each
 * note's full {@code getProducer(-1)} chain) and once through the batched dispatch
 * ({@code BatchedPatternLayerRenderer}), into separate destinations, and asserts
 * the two destination buffers agree to a small RMS.
 *
 * <p>Note generation is deterministic (a note's offset, duration and producer
 * derive from the fixed genome and context), so both renders cover the same notes;
 * the only difference is the rendering algorithm. This confirms the per-piece
 * equivalence results (resample, envelopes, single-note filters, placement) hold
 * together at pattern density — that the batched dispatch produces the right audio,
 * not merely that it fires. The window is one {@link BatchedPatternLayerRenderer#MAX_WINDOW}
 * (a single sub-window), so any residual is the envelope approximation plus the
 * bounded FIR seam at the window edge for notes extending past it.</p>
 */
public class BatchedVsPerNoteRmsTest extends TestSuiteBase implements AudioTestFeatures {

	/** Measure duration in seconds at 120 BPM, 4 beats per measure. */
	private static final double MEASURE_DURATION = 2.0;

	private NoteAudioChoice melodicChoice(DefaultKeyboardTuning tuning) {
		NoteAudioChoice choice = NoteAudioChoice.fromSource("Harmony",
				new FileNoteSource(getNamedTestWavPath("rms_c0.wav", 27.5, 2.0, false),
						WesternChromatic.A0),
				0, 9, true);
		choice.getSources().add(new FileNoteSource(
				getNamedTestWavPath("rms_c1.wav", 32.7, 2.0, false), WesternChromatic.C1));
		choice.getSources().add(new FileNoteSource(
				getNamedTestWavPath("rms_c2.wav", 65.4, 2.0, false), WesternChromatic.C2));
		choice.setTuning(tuning);
		choice.setBias(1.0);
		return choice;
	}

	private AudioSceneContext context(int measures, double measureFrames, int frameCount,
									  PackedCollection destination) {
		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(measures);
		context.setFrames(frameCount);
		context.setFrameForPosition(pos -> (int) (pos * measureFrames));
		context.setTimeForDuration(pos -> pos * MEASURE_DURATION);
		context.setScaleForPosition(pos -> WesternScales.major(WesternChromatic.C4, 1));
		context.setDestination(destination);
		context.setChannels(List.of(
				new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT)));
		return context;
	}

	private void render(PatternLayerManager manager, AudioSceneContext context, int frameCount,
						boolean batched) {
		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = batched;
		try {
			manager.updateDestination(context);
			manager.sum(() -> context, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
					() -> 0, frameCount).get().run();
		} finally {
			PatternLayerManager.enableBatched = previous;
		}
	}

	@Test(timeout = 180000)
	@TestDepth(2)
	public void batchedMatchesPerNote() {
		DefaultKeyboardTuning tuning = new DefaultKeyboardTuning();
		List<NoteAudioChoice> choices = List.of(melodicChoice(tuning));

		double measureFrames = MEASURE_DURATION * OutputLine.sampleRate;
		int measures = 2;
		int frameCount = BatchedPatternLayerRenderer.MAX_WINDOW;

		// Retry genomes until one places melodic notes in the first window and the
		// batched path actually dispatches them (rather than falling back).
		PatternLayerManager manager = null;
		PackedCollection batchedOut = null;
		for (int attempt = 0; attempt < 60 && manager == null; attempt++) {
			PatternLayerManager candidate = new PatternLayerManager(
					choices, new ProjectedGenome(8).addChromosome(), 0, measures, true);
			candidate.setScaleTraversalDepth(3);
			candidate.setLayerCount(3);
			if (candidate.getAllElements(0, measures).isEmpty()) continue;

			PackedCollection dest = new PackedCollection(frameCount);
			BatchedPatternLayerRenderer.resetCounters();
			render(candidate, context(measures, measureFrames, frameCount, dest), frameCount, true);
			if (BatchedPatternLayerRenderer.batchedDispatchCount.get() > 0
					&& BatchedPatternLayerRenderer.fallbackCount.get() == 0) {
				manager = candidate;
				batchedOut = dest;
			}
		}
		Assume.assumeTrue("no genome dispatched a batched melodic window", manager != null);

		PackedCollection perNoteOut = new PackedCollection(frameCount);
		render(manager, context(measures, measureFrames, frameCount, perNoteOut), frameCount, false);

		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		double worst = 0.0;
		for (int i = 0; i < frameCount; i++) {
			double ref = perNoteOut.toDouble(i);
			double diff = ref - batchedOut.toDouble(i);
			sumSqDiff += diff * diff;
			sumSqRef += ref * ref;
			worst = Math.max(worst, Math.abs(diff));
		}
		double rms = Math.sqrt(sumSqDiff / frameCount);
		double refRms = Math.sqrt(sumSqRef / frameCount);
		double relative = refRms > 1e-10 ? rms / refRms : 0.0;

		log("batched vs per-note: refRms=" + refRms + " diffRms=" + rms
				+ " relative=" + relative + " worstAbsDiff=" + worst);

		Assert.assertTrue("per-note render is trivially silent (refRms=" + refRms + ")",
				refRms > 1e-4);
		Assert.assertTrue("batched output diverges from per-note (relative RMS " + relative + ")",
				relative < 0.05);
	}
}
