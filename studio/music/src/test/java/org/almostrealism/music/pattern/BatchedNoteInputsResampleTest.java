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
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedEnvelopeLayers;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudio;
import org.almostrealism.music.notes.SimplePatternNote;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * Verifies that {@link BatchedNoteInputs#from} supplies the RAW (un-resampled)
 * channel — a cached {@code getChannelData(channel, 1.0)} lookup shared by every
 * note using the sample — together with the per-note effective resample ratio, so
 * the batched kernel performs the resample once for the whole batch instead of
 * paying a per-note {@code getChannelData(rate)} interpolation. The note here is
 * shifted one octave DOWN (target C1, sample root C2 → ratio 0.5), so the effective
 * ratio is below unity.
 */
public class BatchedNoteInputsResampleTest extends TestSuiteBase implements AudioTestFeatures {

	private static final int LAYERS = 3;
	private static final int SOURCE_LENGTH = 2048;

	private NoteAudioProvider provider(String name, double freq) {
		return NoteAudioProvider.create(getNamedTestWavPath(name, freq, 2.0, false),
				WesternChromatic.C2, new DefaultKeyboardTuning());
	}

	private PackedCollection fit(PackedCollection raw) {
		PackedCollection out = new PackedCollection(SOURCE_LENGTH);
		out.setMem(0, raw, 0, Math.min(raw.getMemLength(), SOURCE_LENGTH));
		return out;
	}

	@Test(timeout = 120000)
	@TestDepth(2)
	public void gatherSuppliesRawSourceAndEffectiveRatio() {
		NoteAudioProvider[] providers = {
				provider("rs_a.wav", 261.6), provider("rs_b.wav", 196.0), provider("rs_c.wav", 329.6) };
		List<PatternNoteAudio> choices = Arrays.asList(
				new SimplePatternNote(providers[0]),
				new SimplePatternNote(providers[1]),
				new SimplePatternNote(providers[2]));
		DoubleFunction<PatternNoteAudio> audioSelection =
				d -> choices.get(Math.min(LAYERS - 1, (int) (d * LAYERS)));

		PatternNoteFactory factory = new PatternNoteFactory();
		factory.setLayerEnvelopes(ParameterizedEnvelopeLayers.random(LAYERS));
		factory.setVolumeEnvelope(
				ParameterizedVolumeEnvelope.random(ParameterizedVolumeEnvelope.Mode.NOTE_LAYER));
		factory.setFilterEnvelope(
				ParameterizedFilterEnvelope.random(ParameterizedFilterEnvelope.Mode.NOTE_LAYER));

		ParameterSet params = ParameterSet.random();
		PatternNote inner = factory.apply(params, ChannelInfo.Voicing.MAIN, true, 0.1, 0.4, 0.7);
		PatternNote mid = factory.getFilterEnvelope().apply(params, ChannelInfo.Voicing.MAIN, inner);
		PatternNote outer = factory.getVolumeEnvelope().apply(params, ChannelInfo.Voicing.MAIN, mid);

		// Target one octave below the C2 sample root → pitch-down ratio ~0.5.
		WesternChromatic target = WesternChromatic.C1;

		BatchedNoteInputs in = BatchedNoteInputs.from(
				outer, target, 0, 0.02, 0.5, audioSelection, SOURCE_LENGTH);
		Assert.assertNotNull("gather should succeed for the melodic-SSS note", in);

		DefaultKeyboardTuning tuning = new DefaultKeyboardTuning();
		double ratio = tuning.getTone(target).asHertz() / tuning.getTone(WesternChromatic.C2).asHertz();
		Assert.assertTrue("expected a pitch-down ratio (< 1)", ratio < 1.0);

		for (int l = 0; l < LAYERS; l++) {
			WaveDataProvider wave = providers[l].getProvider();
			double effectiveRatio = ratio;
			if (wave.getSampleRate() != OutputLine.sampleRate) {
				effectiveRatio = ratio * wave.getSampleRate() / (double) OutputLine.sampleRate;
			}
			// The gather supplies the per-note effective resample ratio; the kernel resamples.
			Assert.assertEquals("layer " + l + " effective ratio",
					effectiveRatio, in.getRatios()[l], 1e-12);

			// ...and the RAW (un-resampled) channel — a cached lookup, not a per-note resample.
			PackedCollection expected = fit(wave.getChannelData(0, 1.0));
			PackedCollection actual = in.getSources()[l];
			for (int i = 0; i < SOURCE_LENGTH; i++) {
				Assert.assertEquals("layer " + l + " sample " + i,
						expected.toDouble(i), actual.toDouble(i), 1e-9);
			}
		}
	}
}
