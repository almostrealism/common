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

package org.almostrealism.audio.persistence;

import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.notes.NoteAudio;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.audio.synth.AudioSynthesisModel;
import org.almostrealism.audio.synth.AudioSynthesizer;
import org.almostrealism.audio.synth.InterpolatedAudioSynthesisModel;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.io.Console;
import org.almostrealism.persistence.CollectionEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.DoubleStream;

public class GeneratedSourceLibrary {
	private final LibraryDestination library;
	private final Map<String, AudioSynthesisModel> models;

	public GeneratedSourceLibrary(LibraryDestination library) {
		this.library = library;
		this.models = new HashMap<>();
	}

	public void add(String key, AudioSynthesisModel model) {
		models.put(key, model);
	}

	public StatelessSource getSource(String key) {
		AudioSynthesizer synth = new AudioSynthesizer();

		if (models.containsKey(key)) {
			synth.setModel(models.get(key));
		}

		return synth;
	}

	public AudioSynthesisModel getModel(String key) { return models.get(key); }

	public AudioSynthesisModel getModel(NoteAudioProvider provider) {
		KeyboardTuning tuning = provider.getTuning();
		KeyPosition<?> root = provider.getRoot();

		double a1Hz = tuning.getTone(WesternChromatic.A1).asHertz();
		double a4Hz = tuning.getTone(WesternChromatic.A4).asHertz();
		String tuningHash = String.format("%.5f:%.5f", a1Hz, a4Hz);

		String key = provider.getProvider().getIdentifier() +
						":" + root.position() + ":" + tuningHash;
		if (!models.containsKey(key)) {
			add(key, InterpolatedAudioSynthesisModel.create(provider, root, tuning));
		}

		return getModel(key);
	}

	public StatelessSource getSynthesizer(NoteAudio modelInput) {
		if (!(modelInput instanceof NoteAudioProvider provider)) {
			throw new UnsupportedOperationException();
		}

		AudioSynthesizer synth = new AudioSynthesizer(getModel(provider), 2, 5, 4);
		synth.setTuning(provider.getTuning());
		return synth;
	}

	public void save() throws IOException {
		try (LibraryDestination.Writer out = library.out()) {
			Audio.AudioLibraryData.Builder data = Audio.AudioLibraryData.newBuilder();

			int byteCount = 0;

			List<Map.Entry<String, AudioSynthesisModel>> entries = new ArrayList<>(models.entrySet());

			for (int i = 0; i < entries.size(); i++) {
				Audio.SynthesizerModelData d = convert(entries.get(i).getKey(),
						(InterpolatedAudioSynthesisModel) entries.get(i).getValue());
				byteCount += d.getSerializedSize();
				data.addModels(d);

				if (byteCount > AudioLibraryPersistence.batchSize || i == entries.size() - 1) {
					OutputStream o = out.get();

					try {
						data.build().writeTo(o);
						Console.root.features(AudioLibraryPersistence.class)
								.log("Wrote " + data.getRecordingsCount() +
										" recordings (" + byteCount + " bytes)");

						data = Audio.AudioLibraryData.newBuilder();
						byteCount = 0;
						o.flush();
					} finally {
						o.close();
					}
				}
			}
		}
	}

	public void load() {
		library.load().stream().map(Audio.AudioLibraryData::getModelsList).flatMap(List::stream)
				.forEach(d -> models.put(d.getKey(), convert(d)));
	}

	public Audio.SynthesizerModelData convert(String key, InterpolatedAudioSynthesisModel model) {
		Audio.SynthesizerModelData.Builder data = Audio.SynthesizerModelData.newBuilder();
		data.setKey(key);
		data.setLevelSampleRate(model.getSampleRate());
		data.setLevelData(CollectionEncoder.encode(model.getLevelData()));
		DoubleStream.of(model.getFrequencyRatios()).forEach(data::addFrequencyRatios);
		return data.build();
	}

	public InterpolatedAudioSynthesisModel convert(Audio.SynthesizerModelData data) {
		return new InterpolatedAudioSynthesisModel(
				data.getFrequencyRatiosList().stream().mapToDouble(Double::doubleValue).toArray(),
				data.getLevelSampleRate(), CollectionEncoder.decode(data.getLevelData()));
	}
}
