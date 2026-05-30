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
 * Sentinel test proving that the batched dispatch actually fires through the real
 * {@link PatternLayerManager} render path when {@code AR_PATTERN_BATCHED} is on —
 * the assertion the prior integration attempt faked. It builds a melodic pattern
 * over synthetic samples, renders one tick with the batched flag enabled, and
 * asserts {@link BatchedPatternLayerRenderer#batchedDispatchCount} advanced.
 */
public class BatchedDispatchSentinelTest extends TestSuiteBase implements AudioTestFeatures {

	/** Measure duration in seconds at 120 BPM, 4 beats per measure. */
	private static final double MEASURE_DURATION = 2.0;

	private NoteAudioChoice melodicChoice(DefaultKeyboardTuning tuning) {
		NoteAudioChoice choice = NoteAudioChoice.fromSource("Harmony",
				new FileNoteSource(getNamedTestWavPath("sentinel_c0.wav", 27.5, 2.0, false),
						WesternChromatic.A0),
				0, 9, true);
		choice.getSources().add(new FileNoteSource(
				getNamedTestWavPath("sentinel_c1.wav", 32.7, 2.0, false), WesternChromatic.C1));
		choice.getSources().add(new FileNoteSource(
				getNamedTestWavPath("sentinel_c2.wav", 65.4, 2.0, false), WesternChromatic.C2));
		choice.setTuning(tuning);
		choice.setBias(1.0);
		return choice;
	}

	@Test(timeout = 180000)
	@TestDepth(2)
	public void batchedDispatchFires() {
		DefaultKeyboardTuning tuning = new DefaultKeyboardTuning();
		List<NoteAudioChoice> choices = List.of(melodicChoice(tuning));

		double measureFrames = MEASURE_DURATION * OutputLine.sampleRate;
		int measures = 2;
		int frameCount = (int) (measures * measureFrames);

		// Retry genomes until one produces an active melodic pattern (not every
		// genome seeds notes — mirrors AudioSceneTestBase.findWorkingGenomeSeed).
		PatternLayerManager manager = null;
		for (int attempt = 0; attempt < 40 && manager == null; attempt++) {
			PatternLayerManager candidate = new PatternLayerManager(
					choices, new ProjectedGenome(8).addChromosome(), 0, measures, true);
			candidate.setScaleTraversalDepth(3);
			candidate.setLayerCount(3);
			if (!candidate.getAllElements(0, measures).isEmpty()) {
				manager = candidate;
			}
		}
		Assume.assumeTrue("no genome produced an active melodic pattern in "
				+ measures + " measures", manager != null);

		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(measures);
		context.setFrames(frameCount);
		context.setFrameForPosition(pos -> (int) (pos * measureFrames));
		context.setTimeForDuration(pos -> pos * MEASURE_DURATION);
		context.setScaleForPosition(pos -> WesternScales.major(WesternChromatic.C4, 1));
		context.setDestination(new PackedCollection(frameCount));
		context.setChannels(List.of(
				new ChannelInfo(0, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT)));

		BatchedPatternLayerRenderer.resetCounters();
		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = true;
		try {
			manager.updateDestination(context);
			manager.sum(() -> context, ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT,
					() -> 0, frameCount).get().run();
		} finally {
			PatternLayerManager.enableBatched = previous;
		}

		log("batchedDispatchCount=" + BatchedPatternLayerRenderer.batchedDispatchCount
				+ " fallbackCount=" + BatchedPatternLayerRenderer.fallbackCount);

		Assert.assertTrue(
				"batched dispatch should fire for a melodic pattern (counts: batched="
						+ BatchedPatternLayerRenderer.batchedDispatchCount + ", fallback="
						+ BatchedPatternLayerRenderer.fallbackCount + ")",
				BatchedPatternLayerRenderer.batchedDispatchCount > 0);
	}
}
