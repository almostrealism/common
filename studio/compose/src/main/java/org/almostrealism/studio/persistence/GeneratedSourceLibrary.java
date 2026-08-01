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

package org.almostrealism.studio.persistence;

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
import org.almostrealism.persist.assets.CollectionEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.DoubleStream;

/**
 * Library of synthesized audio source models backed by a {@link LibraryDestination}.
 * Models are keyed by a compound string identifier and can be serialised to and
 * deserialised from Protocol Buffers format.
 */
public class GeneratedSourceLibrary {
	/** Destination used for persistent storage of this library's data. */
	private final LibraryDestination library;

	/** Map from compound key to audio synthesis model. */
	private final Map<String, AudioSynthesisModel> models;

	/**
	 * Creates a generated source library backed by the given destination.
	 *
	 * @param library the library destination for persistence
	 */
	public GeneratedSourceLibrary(LibraryDestination library) {
		this.library = library;
		this.models = new HashMap<>();
	}

	/**
	 * Adds a model to the library under the given key.
	 *
	 * @param key   the compound key identifying the model
	 * @param model the synthesis model to add
	 */
	public void add(String key, AudioSynthesisModel model) {
		models.put(key, model);
	}

	/**
	 * Returns a stateless audio source backed by the model with the given key.
	 *
	 * @param key the model key
	 * @return an audio synthesizer configured with the model
	 */
	public StatelessSource getSource(String key) {
		AudioSynthesizer synth = new AudioSynthesizer();

		if (models.containsKey(key)) {
			synth.setModel(models.get(key));
		}

		return synth;
	}

	/**
	 * Returns the synthesis model for the given key, or {@code null} if not found.
	 *
	 * @param key the compound model key
	 */
	public AudioSynthesisModel getModel(String key) { return models.get(key); }

	/**
	 * Returns the synthesis model for the given audio provider, creating and caching
	 * an interpolated model if one does not already exist.
	 *
	 * @param provider the note audio provider whose model to retrieve
	 * @return the synthesis model for the provider
	 */
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

	/**
	 * Returns an audio synthesizer configured with the model for the given note audio input.
	 *
	 * @param modelInput the note audio whose model to use
	 * @return a stateless audio source
	 * @throws UnsupportedOperationException if the input is not a {@link NoteAudioProvider}
	 */
	public StatelessSource getSynthesizer(NoteAudio modelInput) {
		if (!(modelInput instanceof NoteAudioProvider provider)) {
			throw new UnsupportedOperationException();
		}

		AudioSynthesizer synth = new AudioSynthesizer(getModel(provider), 2, 5, 4);
		synth.setTuning(provider.getTuning());
		return synth;
	}

	/**
	 * Serialises all models in this library to the configured destination using
	 * Protocol Buffers. Data is flushed in batches.
	 *
	 * @throws IOException if writing to the destination fails
	 */
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

	/** Loads all models from the configured destination into this library. */
	public void load() {
		library.load().stream().map(Audio.AudioLibraryData::getModelsList).flatMap(List::stream)
				.forEach(d -> models.put(d.getKey(), convert(d)));
	}

	/**
	 * Converts a model and its key to a Protocol Buffers message.
	 *
	 * @param key   the compound key
	 * @param model the model to convert
	 * @return the serialised message
	 */
	public Audio.SynthesizerModelData convert(String key, InterpolatedAudioSynthesisModel model) {
		Audio.SynthesizerModelData.Builder data = Audio.SynthesizerModelData.newBuilder();
		data.setKey(key);
		data.setLevelSampleRate(model.getSampleRate());
		data.setLevelData(CollectionEncoder.encode(model.getLevelData()));
		DoubleStream.of(model.getFrequencyRatios()).forEach(data::addFrequencyRatios);
		return data.build();
	}

	/**
	 * Converts a Protocol Buffers message back into an interpolated synthesis model.
	 *
	 * @param data the serialised model data
	 * @return the reconstructed model
	 */
	public InterpolatedAudioSynthesisModel convert(Audio.SynthesizerModelData data) {
		return new InterpolatedAudioSynthesisModel(
				data.getFrequencyRatiosList().stream().mapToDouble(Double::doubleValue).toArray(),
				data.getLevelSampleRate(), CollectionEncoder.decode(data.getLevelData()));
	}
}
