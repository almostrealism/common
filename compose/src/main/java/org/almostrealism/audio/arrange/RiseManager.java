/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.arrange;

import io.almostrealism.cycle.Setup;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationWithInfo;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.audio.health.HealthComputationAdapter;
import org.almostrealism.audio.notes.AutomatedPitchNoteAudio;
import org.almostrealism.audio.notes.NoteAudioContext;
import org.almostrealism.audio.notes.PatternNote;
import org.almostrealism.audio.notes.PatternNoteAudio;
import org.almostrealism.audio.notes.TremoloAudioFilter;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.audio.pattern.PatternFeatures;
import org.almostrealism.audio.synth.AudioSynthesizer;
import org.almostrealism.audio.synth.NoiseGenerator;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedChromosome;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class RiseManager implements Setup, PatternFeatures, CellFeatures {
	public static final double riseDuration = HealthComputationAdapter.standardDurationSeconds;

	private final ProjectedChromosome chromosome;
	private final int sampleRate;

	private final AudioSynthesizer synth;
	private final NoiseGenerator noise;
	private final OperationList setup;

	private PackedCollection destination;

	public RiseManager(ProjectedChromosome chromosome, Supplier<AudioSceneContext> context, int sampleRate) {
		this.chromosome = chromosome;
		this.sampleRate = sampleRate;
		this.setup = new OperationList("RiseManager Setup");
		this.synth = new AudioSynthesizer(2, 2);
		this.noise = new NoiseGenerator();

		PatternNoteAudio riseAudio = new AutomatedPitchNoteAudio(synth, riseDuration);
		PatternNote riseNote = new PatternNote(riseAudio, new TremoloAudioFilter());

		PatternNoteAudio noiseAudio = new AutomatedPitchNoteAudio(noise, riseDuration);
		PatternNote noiseNote = new PatternNote(noiseAudio, new TremoloAudioFilter());

		List<PatternElement> elements = new ArrayList<>();
		elements.add(new PatternElement(riseNote, 0.0));
		elements.add(new PatternElement(noiseNote, 0.0));
		elements.get(0).setAutomationParameters(new PackedCollection(6).fill(0.5));
		elements.get(1).setAutomationParameters(new PackedCollection(6).fill(0.5));

		setup.add(OperationWithInfo.of(new OperationMetadata("RiseManager.render", "RiseManager.render"),
				() -> () -> {
					AudioSceneContext ctx = context.get();
					render(ctx, new NoteAudioContext(), elements, false, 0);
					destination = ctx.getDestination();
				}
		));
	}

	public ProjectedChromosome getChromosome() { return chromosome; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	public CellList getRise(int frames) {
		Producer<PackedCollection> audio =
				func(shape(frames), args -> destination, false);
		return w(PolymorphicAudioData.supply(PackedCollection.factory()),
				sampleRate, frames, null, null, traverse(0, audio));
	}
}
