/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio.sources.test;

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.arrange.AudioSceneContext;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudioContext;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.notes.PatternNote;
import org.almostrealism.audio.notes.SimplePatternNote;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternFeatures;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.FrequencyRescalingSourceAggregator;
import org.almostrealism.audio.sources.ModularSourceAggregator;
import org.almostrealism.audio.sources.VolumeRescalingSourceAggregator;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RescalingAggregationTests implements PatternFeatures, TestFeatures {
	private final int sampleRate = OutputLine.sampleRate;

	@Test
	public void loadAggregated() throws IOException {
		WaveData data = WaveData.load(new File("Library/organ.wav"));
		PackedCollection eq = data.aggregatedFft(true);
		log(eq.getShape());

		int total = 0;
		for (int i = 0; i < eq.getShape().length(0); i++) {
			if (eq.toDouble(i) > 0.1) {
				total++;
			}
		}

		log(total);
		Assert.assertTrue(total > 200);
	}

	@Test
	public void rescaleVolume() throws IOException {
		VolumeRescalingSourceAggregator aggregator = new VolumeRescalingSourceAggregator();

		WaveData input = WaveData.load(new File("Library/organ.wav"));
		WaveData filter = WaveData.load(new File("Library/Snare Gold 1.wav"));

		Producer<PackedCollection> aggregated = aggregator.aggregate(
				new BufferDetails(sampleRate, input.getDuration()),
				null, null,
				cp(input.getChannelData(0)),
				cp(filter.getChannelData(0)));

		WaveData out = new WaveData(Process.optimized(aggregated).get().evaluate(), sampleRate);
		out.save(new File("results/rescaling-volume.wav"));
	}


	@Test
	public void rescaleFrequency() throws IOException {
		FrequencyRescalingSourceAggregator aggregator = new FrequencyRescalingSourceAggregator();

		WaveData input = WaveData.load(new File("Library/SN_Forever_Future.wav"));
		WaveData filter = WaveData.load(new File("Library/organ.wav"));

		Producer<PackedCollection> aggregated = aggregator.aggregate(
				new BufferDetails(sampleRate, 28.0),
				null, null,
				cp(input.getChannelData(0)),
				cp(filter.getChannelData(0)));

		WaveData out = new WaveData(Process.optimized(aggregated).get().evaluate(), sampleRate);
		out.save(new File("results/rescaling-frequency.wav"));
	}

	@Test
	public void rescaleModular1() throws IOException {
		modularRescale("rescaling-modular-1",
				ModularSourceAggregator.InputType.SOURCE,
				ModularSourceAggregator.InputType.FREQUENCY,
				ModularSourceAggregator.InputType.VOLUME_ENVELOPE);
	}

	@Test
	public void rescaleModular2() throws IOException {
		modularRescale("rescaling-modular-2",
				ModularSourceAggregator.InputType.SOURCE,
				ModularSourceAggregator.InputType.SOURCE,
				ModularSourceAggregator.InputType.VOLUME_ENVELOPE);
	}

	public void modularRescale(String name, ModularSourceAggregator.InputType... inputs) throws IOException {
		ModularSourceAggregator aggregator = new ModularSourceAggregator(inputs);

		WaveData input = WaveData.load(new File("Library/organ.wav"));
		WaveData filter = WaveData.load(new File("Library/SN_Forever_Future.wav"));
		WaveData env = WaveData.load(new File("Library/Snare Gold 1.wav"));

		Producer<PackedCollection> aggregated = aggregator.aggregate(
				new BufferDetails(sampleRate, input.getDuration()),
				null, null,
				cp(input.getChannelData(0)),
				cp(filter.getChannelData(0)),
				cp(env.getChannelData(0)));

		WaveData out = new WaveData(Process.optimized(aggregated).get().evaluate(), sampleRate);
		out.save(new File("results/" + name + ".wav"));
	}

	@Test
	public void aggregatedPattern() {
		// Define the shared parameters, including how notes should be
		// tuned and a root for the scale and the synth
		double duration = 8.0;
		KeyboardTuning tuning = new DefaultKeyboardTuning();
		WesternChromatic root = WesternChromatic.C3;

		PatternNote choiceNote = new PatternNote(0.25, 0.65, 0.9);
		choiceNote.setTuning(tuning);
		choiceNote.setAggregationChoice(0.4);

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
		audioContext.setAudioSelection((choice) -> {
					if (choice < 0.5) {
						return new SimplePatternNote(NoteAudioProvider
								.create("Library/organ.wav", WesternChromatic.D3, tuning));
					} else if (choice < 0.7) {
						return new SimplePatternNote(NoteAudioProvider
								.create("Library/SN_Forever_Future.wav", WesternChromatic.D3, tuning));
					} else {
						return new SimplePatternNote(NoteAudioProvider
								.create("Library/Snare Gold 1.wav", WesternChromatic.D3, tuning));
					}
				});

		// Create the elements of the composition, leveraging
		// the notes that have been defined in multiple places
		// to create a pattern of 4 elements
		List<PatternElement> elements = new ArrayList<>();
		elements.add(new PatternElement(choiceNote, 2.5));
		elements.add(new PatternElement(choiceNote, 6.5));

		// Adjust the position on the major scale for each of the
		// elements in the composition
		elements.get(0).setScalePosition(List.of(0.0));
		elements.get(1).setScalePosition(List.of(0.5));

		// Render the composition
		render(sceneContext, audioContext, elements, true, 0.0);

		// Save the composition to a file
		new WaveData(sceneContext.getDestination().traverse(1), sampleRate)
				.save(new File("results/aggregated-pattern.wav"));
	}
}