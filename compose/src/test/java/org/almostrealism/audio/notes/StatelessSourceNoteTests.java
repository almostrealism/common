/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.notes;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternFeatures;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.audio.synth.AudioSynthesizer;
import org.almostrealism.audio.synth.NoiseGenerator;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class StatelessSourceNoteTests implements CellFeatures, SamplingFeatures, PatternFeatures {
	int sampleRate = OutputLine.sampleRate;

	@Test
	public void sineAndSnare() {
		// Define the shared parameters, including how notes should be
		// tuned and a root for the scale and the synth
		double duration = 8.0;
		KeyboardTuning tuning = new DefaultKeyboardTuning();
		WesternChromatic root = WesternChromatic.C4;

		// Settings for the synth note
		double amp = 0.25;
		int frames = (int) (2.0 * sampleRate);

		// Source for the synth note
		StatelessSource sine = (buffer, params, frequency) -> sampling(sampleRate, () -> {
			CollectionProducer t =
					integers(0, frames).divide(sampleRate);

			// Frequency is a function of time, but to avoid
			// the possibility of a note shifting tone while
			// being played the time parameter is explicitly
			// provided as 0.0 (the start of the note)
			Producer<PackedCollection> f =
					frequency.getResultant(c(0.0));
			return sin(t.multiply(2 * Math.PI).multiply(f)).multiply(amp);
		});

		// Define the synth note
		StatelessSourceNoteAudioTuned audio = new StatelessSourceNoteAudioTuned(sine, 2.0);
		PatternNote sineNote = new PatternNote(List.of(audio));
		sineNote.setTuning(tuning);

		// Define a sampler note that will use the parameter 0.5
		// to choose which source to voice
		PatternNote choiceNote = new PatternNote(0.5);
		choiceNote.setTuning(tuning);

		// Setup context for rendering the audio, including the scale,
		// the way to translate positions into audio frames, and the
		// destination for the audio
		AudioSceneContext sceneContext = new AudioSceneContext();
		sceneContext.setFrameForPosition(pos -> (int) (pos * sampleRate));
		sceneContext.setScaleForPosition(pos -> WesternScales.major(root, 1));
		sceneContext.setDestination(new PackedCollection((int) (duration * sampleRate)));

		// Setup context for voicing the notes, including the library
		// of samples to use (choiceNote will select from those)
		NoteAudioContext audioContext = new NoteAudioContext();
		audioContext.setNextNotePosition(pos -> duration);
		audioContext.setAudioSelection((choice) ->
				new SimplePatternNote(NoteAudioProvider
						.create("Library/Snare Gold 1.wav",WesternChromatic.D3, tuning)));

		// Create the elements of the composition, leveraging
		// the notes that have been defined in multiple places
		// to create a pattern of 4 elements
		List<PatternElement> elements = new ArrayList<>();
		elements.add(new PatternElement(sineNote, 0.0));
		elements.add(new PatternElement(choiceNote, 2.5));
		elements.add(new PatternElement(sineNote, 4.0));
		elements.add(new PatternElement(choiceNote, 6.5));

		// Adjust the position on the major scale for each of the
		// elements in the composition
		elements.get(0).setScalePosition(List.of(0.0));
		elements.get(1).setScalePosition(List.of(0.3));
		elements.get(2).setScalePosition(List.of(0.5));
		elements.get(3).setScalePosition(List.of(0.5));

		// Render the composition
		render(sceneContext, audioContext, elements, true, 0.0);

		// Save the composition to a file
		new WaveData(sceneContext.getDestination().traverse(1), sampleRate)
				.save(new File("results/sine-notes.wav"));
	}

	@Test
	public void riser() {
		double duration = 8.0;

		// Define the synth note
		AudioSynthesizer synth = new AudioSynthesizer(2, 2);
		AutomatedPitchNoteAudio riser = new AutomatedPitchNoteAudio(synth, 8.0);
		PatternNote riseNote = new PatternNote(riser, new TremoloAudioFilter());

		// Define the noise note
		AutomatedPitchNoteAudio noise = new AutomatedPitchNoteAudio(new NoiseGenerator(), 8.0);
		PatternNote noiseNote = new PatternNote(noise, new TremoloAudioFilter());

		// Setup context for rendering the audio, including the scale,
		// the way to translate positions into audio frames, and the
		// destination for the audio
		AudioSceneContext sceneContext = new AudioSceneContext();
		sceneContext.setFrameForPosition(pos -> (int) (pos * sampleRate));
		sceneContext.setScaleForPosition(pos -> WesternScales.major(WesternChromatic.C3, 1));
		sceneContext.setDestination(new PackedCollection((int) (duration * sampleRate)));
		sceneContext.setAutomationLevel(
				params -> t -> divide(t, c(2 * duration)));

		// Setup context for voicing the notes
		NoteAudioContext audioContext = new NoteAudioContext();
		audioContext.setNextNotePosition(pos -> duration);

		// Create the elements of the composition
		List<PatternElement> elements = new ArrayList<>();
		elements.add(new PatternElement(riseNote, 0.0));
		elements.add(new PatternElement(noiseNote, 0.0));

		// Render the composition
		render(sceneContext, audioContext, elements, true, 0.0);

		// Save the composition to a file
		new WaveData(sceneContext.getDestination().traverse(1), sampleRate)
				.save(new File("results/riser.wav"));
	}
}
