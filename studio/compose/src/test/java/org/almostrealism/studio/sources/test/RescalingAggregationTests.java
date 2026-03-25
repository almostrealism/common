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

package org.almostrealism.studio.sources.test;

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.music.notes.NoteAudioContext;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.SimplePatternNote;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternFeatures;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.FrequencyRescalingSourceAggregator;
import org.almostrealism.audio.sources.ModularSourceAggregator;
import org.almostrealism.audio.sources.VolumeRescalingSourceAggregator;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RescalingAggregationTests extends TestSuiteBase implements PatternFeatures, AudioTestFeatures {
	private final int sampleRate = OutputLine.sampleRate;

	@Test(timeout = 60000)
	@TestProperties(knownIssue = true)
	public void loadAggregated() throws IOException {
		// Use synthetic audio for testing FFT aggregation
		WaveData data = WaveData.load(getTestWavFile(440.0, 2.0));
		PackedCollection eq = data.aggregatedFft(true);
		log(eq.getShape());

		int total = 0;
		for (int i = 0; i < eq.getShape().length(0); i++) {
			if (eq.toDouble(i) > 0.1) {
				total++;
			}
		}

		log(total);
		// Synthetic 440Hz sine has fewer harmonics than organ, so lower threshold
		Assert.assertTrue(total > 10);
	}

	@Test(timeout = 60000)
	public void rescaleVolume() throws IOException {
		VolumeRescalingSourceAggregator aggregator = new VolumeRescalingSourceAggregator();

		// Use synthetic audio at different frequencies
		WaveData input = WaveData.load(getTestWavFile(440.0, 2.0));
		WaveData filter = WaveData.load(getTestWavFile(880.0, 1.0));

		Producer<PackedCollection> aggregated = aggregator.aggregate(
				new BufferDetails(sampleRate, input.getDuration()),
				null, null,
				cp(input.getChannelData(0)),
				cp(filter.getChannelData(0)));

		WaveData out = new WaveData(Process.optimized(aggregated).get().evaluate(), sampleRate);
		out.save(new File("results/rescaling-volume.wav"));
	}


	@Test(timeout = 60000)
	public void rescaleFrequency() throws IOException {
		FrequencyRescalingSourceAggregator aggregator = new FrequencyRescalingSourceAggregator();

		// Use synthetic audio at different frequencies
		WaveData input = WaveData.load(getTestWavFile(220.0, 2.0));
		WaveData filter = WaveData.load(getTestWavFile(440.0, 2.0));

		Producer<PackedCollection> aggregated = aggregator.aggregate(
				new BufferDetails(sampleRate, 2.0),
				null, null,
				cp(input.getChannelData(0)),
				cp(filter.getChannelData(0)));

		WaveData out = new WaveData(Process.optimized(aggregated).get().evaluate(), sampleRate);
		out.save(new File("results/rescaling-frequency.wav"));
	}

	@Test(timeout = 60000)
	public void rescaleModular1() throws IOException {
		modularRescale("rescaling-modular-1",
				ModularSourceAggregator.InputType.SOURCE,
				ModularSourceAggregator.InputType.FREQUENCY,
				ModularSourceAggregator.InputType.VOLUME_ENVELOPE);
	}

	@Test(timeout = 60000)
	public void rescaleModular2() throws IOException {
		modularRescale("rescaling-modular-2",
				ModularSourceAggregator.InputType.SOURCE,
				ModularSourceAggregator.InputType.SOURCE,
				ModularSourceAggregator.InputType.VOLUME_ENVELOPE);
	}

	public void modularRescale(String name, ModularSourceAggregator.InputType... inputs) throws IOException {
		ModularSourceAggregator aggregator = new ModularSourceAggregator(inputs);

		// Use synthetic audio at different frequencies
		WaveData input = WaveData.load(getTestWavFile(440.0, 2.0));
		WaveData filter = WaveData.load(getTestWavFile(220.0, 2.0));
		WaveData env = WaveData.load(getTestWavFile(880.0, 1.0));

		Producer<PackedCollection> aggregated = aggregator.aggregate(
				new BufferDetails(sampleRate, input.getDuration()),
				null, null,
				cp(input.getChannelData(0)),
				cp(filter.getChannelData(0)),
				cp(env.getChannelData(0)));

		WaveData out = new WaveData(Process.optimized(aggregated).get().evaluate(), sampleRate);
		out.save(new File("results/" + name + ".wav"));
	}

	@Test(timeout = 60000)
	public void aggregatedPattern() {
		// Define the shared parameters, including how notes should be
		// tuned and a root for the scale and the synth
		double duration = 8.0;
		KeyboardTuning tuning = new DefaultKeyboardTuning();
		WesternChromatic root = WesternChromatic.C3;

		// Get test audio paths for CI compatibility
		String testAudio1 = getTestWavFile(440.0, 2.0).getAbsolutePath();
		String testAudio2 = getTestWavFile(220.0, 2.0).getAbsolutePath();
		String testAudio3 = getTestWavFile(880.0, 1.0).getAbsolutePath();

		PatternNote choiceNote = new PatternNote(0.25, 0.65, 0.9);
		choiceNote.setTuning(tuning);
		choiceNote.setAggregationChoice(0.4);

		// Setup context for rendering the audio, including the scale,
		// the way to translate positions into audio frames, and the
		// destination for the audio
		AudioSceneContext sceneContext = new AudioSceneContext();
		sceneContext.setFrameForPosition(pos -> (int) (pos * sampleRate));
		sceneContext.setTimeForDuration(pos -> duration);
		sceneContext.setScaleForPosition(pos -> WesternScales.major(root, 1));
		sceneContext.setDestination(new PackedCollection((int) (duration * sampleRate)));

		// Setup context for voicing the notes, using synthetic test audio
		NoteAudioContext audioContext = new NoteAudioContext();
		audioContext.setNextNotePosition(pos -> duration);
		audioContext.setAudioChannel(ChannelInfo.StereoChannel.LEFT);
		audioContext.setAudioSelection((choice) -> {
					if (choice < 0.5) {
						return new SimplePatternNote(NoteAudioProvider
								.create(testAudio1, WesternChromatic.D3, tuning));
					} else if (choice < 0.7) {
						return new SimplePatternNote(NoteAudioProvider
								.create(testAudio2, WesternChromatic.D3, tuning));
					} else {
						return new SimplePatternNote(NoteAudioProvider
								.create(testAudio3, WesternChromatic.D3, tuning));
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
		render(sceneContext, audioContext, elements, true, 0.0,
				0, sceneContext.getDestination().getShape().getCount(), null);

		// Save the composition to a file
		new WaveData(sceneContext.getDestination().traverse(1), sampleRate)
				.save(new File("results/aggregated-pattern.wav"));
	}
}