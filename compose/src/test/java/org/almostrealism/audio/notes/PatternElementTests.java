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

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternFeatures;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.collect.PackedCollection;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PatternElementTests implements CellFeatures, SamplingFeatures, PatternFeatures {
	int sampleRate = OutputLine.sampleRate;

	@Test
	public void pattern() {
		// Define the shared parameters, including how notes should be
		// tuned and a root for the scale and the synth
		double duration = 8.0;
		KeyboardTuning tuning = new DefaultKeyboardTuning();
		WesternChromatic root = WesternChromatic.C3;

		// Define a sampler note that will use the parameter 0.5
		// to choose which source to voice
		PatternNote choiceNote = new PatternNote(0.5);
		choiceNote.setTuning(tuning);

		// Setup context for rendering the audio, including the scale,
		// the way to translate positions into audio frames, and the
		// destination for the audio
		AudioSceneContext sceneContext = new AudioSceneContext();
		sceneContext.setFrameForPosition(pos -> (int) (pos * sampleRate));
		sceneContext.setTimeForDuration(pos -> duration);
		sceneContext.setScaleForPosition(pos -> WesternScales.major(root, 1));
		sceneContext.setDestination(new PackedCollection((int) (duration * sampleRate)));

		// Setup context for voicing the notes, including the library
		// of samples to use (choiceNote will select from those)
		NoteAudioContext audioContext = new NoteAudioContext();
		audioContext.setNextNotePosition(pos -> duration);
		audioContext.setAudioSelection((choice) ->
				new SimplePatternNote(NoteAudioProvider
						.create("Library/Triangle MS10 C3.wav", WesternChromatic.C3, tuning)));

		// Create the elements of the composition, leveraging
		// the notes that have been defined in multiple places
		// to create a pattern of 4 elements
		List<PatternElement> elements = new ArrayList<>();
		elements.add(new PatternElement(choiceNote, 0.0));
		elements.add(new PatternElement(choiceNote, 2.5));
		elements.add(new PatternElement(choiceNote, 4.0));
		elements.add(new PatternElement(choiceNote, 6.5));

		// Adjust the position on the major scale for each of the
		// elements in the composition
		elements.get(0).setScalePosition(List.of(0.0));
		elements.get(1).setScalePosition(List.of(0.3));
		elements.get(2).setScalePosition(List.of(0.5));
		elements.get(3).setScalePosition(List.of(0.8));

		// Render the composition
		render(sceneContext, audioContext, elements, true, 0.0);

		// Save the composition to a file
		new WaveData(sceneContext.getDestination().traverse(1), sampleRate)
				.save(new File("results/sample-notes.wav"));
	}
}
