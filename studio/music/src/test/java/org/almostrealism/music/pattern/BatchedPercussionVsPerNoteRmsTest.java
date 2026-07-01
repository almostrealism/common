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
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;

/**
 * End-to-end equivalence check for the batched <em>percussion</em> dispatch — the
 * percussion analogue of {@link BatchedVsPerNoteRmsTest}. Renders the same
 * non-melodic (percussion) pattern window once through the production per-note path
 * ({@code renderPerNote}) and once through the batched percussion dispatch
 * ({@code BatchedPatternLayerRenderer.dispatchWindowPercussion}), into separate
 * destinations, and asserts the two buffers agree to a small RMS.
 *
 * <p>A percussion note is the strict subset of a melodic note: still a three-source
 * SSS sum, but at unity pitch (the per-note path passes a {@code null} key for
 * non-melodic notes, so both paths resample sample-rate-only), with no per-layer
 * envelope and no filter envelope, and a volume envelope only on the WET voicing.
 * Both voicings are exercised: MAIN (dry, no volume envelope) and WET (volume
 * envelope), confirming the volume envelope is applied only to the wet voicing and
 * that the wet/dry dispatch selection is correct.</p>
 */
public class BatchedPercussionVsPerNoteRmsTest extends TestSuiteBase implements AudioTestFeatures {

	/** Measure duration in seconds at 120 BPM, 4 beats per measure. */
	private static final double MEASURE_DURATION = 2.0;

	/** Maximum relative RMS divergence accepted between the batched and per-note renders. */
	private static final double RMS_TOLERANCE = 0.05;

	/**
	 * Builds a {@link NoteAudioChoice} with three percussive (noise-burst) note
	 * sources and the given keyboard tuning applied. The roots are arbitrary: a
	 * percussion note renders at unity pitch, so the roots affect only the
	 * (path-identical) duration computation, not the audio.
	 */
	private NoteAudioChoice percussionChoice(DefaultKeyboardTuning tuning) {
		// melodic=false: the choice's melodic flag drives note SHAPE generation
		// (NoteAudioChoice.apply -> PatternElementFactory.apply(..., isMelodic(), ...)),
		// so a non-melodic choice yields the bare percussion-SSS shape.
		NoteAudioChoice choice = NoteAudioChoice.fromSource("Percussion",
				new FileNoteSource(getNamedTestWavPath("rms_perc0.wav", 60.0, 0.5, true),
						WesternChromatic.C2),
				0, 9, false);
		choice.getSources().add(new FileNoteSource(
				getNamedTestWavPath("rms_perc1.wav", 110.0, 0.5, true), WesternChromatic.C2));
		choice.getSources().add(new FileNoteSource(
				getNamedTestWavPath("rms_perc2.wav", 220.0, 0.5, true), WesternChromatic.C2));
		choice.setTuning(tuning);
		choice.setBias(1.0);
		return choice;
	}

	/**
	 * Creates an {@link AudioSceneContext} configured for the given measure count,
	 * frame rate, total frame count, output destination buffer, and channel voicing.
	 */
	private AudioSceneContext context(int measures, double measureFrames, int frameCount,
									  PackedCollection destination, ChannelInfo.Voicing voicing) {
		AudioSceneContext context = new AudioSceneContext();
		context.setMeasures(measures);
		context.setFrames(frameCount);
		context.setFrameForPosition(pos -> (int) (pos * measureFrames));
		context.setTimeForDuration(pos -> pos * MEASURE_DURATION);
		context.setScaleForPosition(pos -> WesternScales.major(WesternChromatic.C4, 1));
		context.setDestination(destination);
		context.setChannels(List.of(
				new ChannelInfo(0, voicing, ChannelInfo.StereoChannel.LEFT)));
		return context;
	}

	/**
	 * Renders a single window of the pattern into the destination buffer contained in
	 * {@code context}, switching the batched-dispatch flag to the requested mode and
	 * restoring the previous value afterwards.
	 */
	private void render(PatternLayerManager manager, AudioSceneContext context, int frameCount,
						ChannelInfo.Voicing voicing, boolean batched) {
		boolean previous = PatternLayerManager.enableBatched;
		PatternLayerManager.enableBatched = batched;
		try {
			manager.updateDestination(context);
			manager.sum(() -> context, voicing, ChannelInfo.StereoChannel.LEFT,
					() -> 0, frameCount).get().run();
		} finally {
			PatternLayerManager.enableBatched = previous;
		}
	}

	/**
	 * Computes the relative RMS of the batched render versus the per-note render over
	 * the window, logs the figures, and asserts the per-note render is non-silent and
	 * the batched render agrees within {@link #RMS_TOLERANCE}.
	 */
	private void assertParity(PackedCollection perNoteOut, PackedCollection batchedOut,
							  int frameCount, String label) {
		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		double sumSqBatched = 0.0;
		double worst = 0.0;
		for (int i = 0; i < frameCount; i++) {
			double ref = perNoteOut.toDouble(i);
			double bat = batchedOut.toDouble(i);
			double diff = ref - bat;
			sumSqDiff += diff * diff;
			sumSqRef += ref * ref;
			sumSqBatched += bat * bat;
			worst = Math.max(worst, Math.abs(diff));
		}
		double rms = Math.sqrt(sumSqDiff / frameCount);
		double refRms = Math.sqrt(sumSqRef / frameCount);
		double batchedRms = Math.sqrt(sumSqBatched / frameCount);
		double relative = refRms > 1e-10 ? rms / refRms : 0.0;

		log(label + " batched vs per-note: refRms=" + refRms + " batchedRms=" + batchedRms
				+ " diffRms=" + rms + " relative=" + relative + " worstAbsDiff=" + worst);

		Assert.assertTrue(label + " batched render is trivially silent (batchedRms=" + batchedRms + ")",
				batchedRms > 1e-4);
		Assert.assertTrue(label + " per-note render is trivially silent (refRms=" + refRms + ")",
				refRms > 1e-4);
		Assert.assertTrue(label + " batched output diverges from per-note (relative RMS " + relative + ")",
				relative < RMS_TOLERANCE);
	}

	/**
	 * Verifies that the batched percussion renderer produces output within 5% relative
	 * RMS of the per-note renderer for the same pattern window, for both the dry (MAIN)
	 * and wet (volume-enveloped) voicings, and that the percussion dispatch path fires.
	 */
	@Test(timeout = 180000)
	@TestDepth(2)
	public void batchedPercussionMatchesPerNote() {
		DefaultKeyboardTuning tuning = new DefaultKeyboardTuning();
		List<NoteAudioChoice> choices = List.of(percussionChoice(tuning));

		double measureFrames = MEASURE_DURATION * OutputLine.sampleRate;
		int measures = 2;
		int frameCount = BatchedPatternLayerRenderer.MAX_WINDOW;

		// Retry genomes until one places percussion notes in the first window and the
		// batched path actually dispatches them (rather than falling back) for both
		// the MAIN and WET voicings.
		PatternLayerManager manager = null;
		PackedCollection mainBatched = null;
		PackedCollection wetBatched = null;
		for (int attempt = 0; attempt < 60 && manager == null; attempt++) {
			PatternLayerManager candidate = new PatternLayerManager(
					choices, new ProjectedGenome(8).addChromosome(), 0, measures, false);
			candidate.setScaleTraversalDepth(3);
			candidate.setLayerCount(3);
			if (candidate.getAllElements(0, measures).isEmpty()) continue;

			PackedCollection destMain = new PackedCollection(frameCount);
			BatchedPatternLayerRenderer.resetCounters();
			render(candidate, context(measures, measureFrames, frameCount, destMain,
					ChannelInfo.Voicing.MAIN), frameCount, ChannelInfo.Voicing.MAIN, true);
			if (BatchedPatternLayerRenderer.batchedDispatchCount.get() == 0
					|| BatchedPatternLayerRenderer.fallbackCount.get() != 0) {
				continue;
			}

			PackedCollection destWet = new PackedCollection(frameCount);
			BatchedPatternLayerRenderer.resetCounters();
			render(candidate, context(measures, measureFrames, frameCount, destWet,
					ChannelInfo.Voicing.WET), frameCount, ChannelInfo.Voicing.WET, true);
			if (BatchedPatternLayerRenderer.batchedDispatchCount.get() == 0
					|| BatchedPatternLayerRenderer.fallbackCount.get() != 0) {
				continue;
			}

			manager = candidate;
			mainBatched = destMain;
			wetBatched = destWet;
		}
		Assume.assumeTrue("no genome dispatched a batched percussion window", manager != null);

		// Confirm the percussion classifier fired: at least one element resolves to the
		// percussion-SSS shape for both the dry MAIN note and the wet (volume) note.
		boolean firedMain = manager.getAllElements(0, measures).stream()
				.map(e -> e.getNote(ChannelInfo.Voicing.MAIN))
				.anyMatch(n -> n != null && BatchedNoteInputs.isPercussionSssShape(n));
		boolean firedWet = manager.getAllElements(0, measures).stream()
				.map(e -> e.getNote(ChannelInfo.Voicing.WET))
				.anyMatch(n -> n != null && BatchedNoteInputs.isPercussionSssShape(n));
		Assert.assertTrue("no element classified as the percussion-SSS MAIN shape", firedMain);
		Assert.assertTrue("no element classified as the percussion-SSS WET shape", firedWet);

		PackedCollection mainPerNote = new PackedCollection(frameCount);
		render(manager, context(measures, measureFrames, frameCount, mainPerNote,
				ChannelInfo.Voicing.MAIN), frameCount, ChannelInfo.Voicing.MAIN, false);
		assertParity(mainPerNote, mainBatched, frameCount, "MAIN(dry)");

		PackedCollection wetPerNote = new PackedCollection(frameCount);
		render(manager, context(measures, measureFrames, frameCount, wetPerNote,
				ChannelInfo.Voicing.WET), frameCount, ChannelInfo.Voicing.WET, false);
		assertParity(wetPerNote, wetBatched, frameCount, "WET(volume)");
	}
}
