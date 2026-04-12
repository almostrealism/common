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

package org.almostrealism.studio.generative;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.almostrealism.audio.notes.NoteAudio;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.music.notes.NoteSourceProvider;
import org.almostrealism.util.KeyUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stateful ML audio generator that manages source audio conditioning and generated
 * audio results. The generator transitions through lifecycle states as it refreshes
 * its model and produces new audio samples.
 */
public class Generator {
	/** Unique identifier for this generator. */
	private String id;

	/** Human-readable name for this generator. */
	private String name;

	/** Current lifecycle state. */
	private State state;

	/** Identifiers of audio sources used as conditioning input. */
	private List<String> sources;

	/** Generated audio results produced by the most recent generation call. */
	private List<NoteAudio> results;

	/** Provider for resolving audio sources by identifier. */
	private NoteSourceProvider sourceProvider;

	/** Back-end generation provider used to refresh and generate audio. */
	private GenerationProvider generationProvider;

	/** Creates a generator with a generated ID and initial {@link State#NONE} state. */
	public Generator() {
		this(KeyUtils.generateKey(), "Generator", State.NONE);
	}

	/**
	 * Creates a generator with the given identifier, name, and state.
	 *
	 * @param id    unique identifier
	 * @param name  human-readable name
	 * @param state initial lifecycle state
	 */
	public Generator(String id, String name, State state) {
		this.id = id;
		this.name = name;
		this.state = state;
		this.sources = new ArrayList<>();
		this.results = new ArrayList<>();
	}

	public String getId() { return id; }
	public void setId(String id) { this.id = id; }

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public State getState() { return state; }
	public void setState(State state) { this.state = state; }

	public List<String> getSources() { return sources; }
	public void setSources(List<String> sources) { this.sources = sources; }

	public List<NoteAudio> getResults() { return results; }
	public void setResults(List<NoteAudio> results) { this.results = results; }

	@JsonIgnore
	public NoteSourceProvider getSourceProvider() { return sourceProvider; }

	@JsonIgnore
	public void setSourceProvider(NoteSourceProvider sourceProvider) { this.sourceProvider = sourceProvider; }

	@JsonIgnore
	public GenerationProvider getGenerationProvider() { return generationProvider; }

	@JsonIgnore
	public void setGenerationProvider(GenerationProvider provider) { this.generationProvider = provider; }

	/**
	 * Refreshes the generation model using the currently configured sources.
	 * The generator must not already be refreshing or generating when this is called.
	 *
	 * @return {@code false} if no sources were available; {@code true} if refresh was triggered
	 * @throws IllegalStateException if the generator is currently busy
	 */
	public boolean refresh() {
		if (state == State.REFRESHING || state == State.GENERATING) {
			throw new IllegalStateException("Generator is busy");
		}

		List<NoteAudio> sources =
				getSources().stream()
						.map(sourceProvider::getSource)
						.flatMap(List::stream)
						.map(NoteAudioSource::getNotes)
						.flatMap(List::stream)
						.collect(Collectors.toList());

		if (sources.isEmpty()) return false;

		state = State.REFRESHING;
		boolean success = generationProvider.refresh(KeyUtils.generateKey(), id, sources);
		state = success ? State.READY : State.NONE;
		return true;
	}

	/**
	 * Generates audio samples using the refreshed model.
	 *
	 * @param count the number of samples to generate
	 * @throws IllegalStateException if the generator is not in the {@link State#READY} state
	 */
	public void generate(int count) {
		if (state != State.READY) {
			throw new IllegalStateException("Generator is not ready");
		}

		state = State.GENERATING;
		List<NoteAudio> results = generationProvider.generate(KeyUtils.generateKey(), id, count);
		if (results != null) {
			getResults().addAll(results);
			state = State.READY;
		}
	}

	/** Lifecycle state of a {@link Generator}. */
	public enum State {
		/** The generator has not been initialised or has been reset. */
		NONE,

		/** The generator is currently refreshing its model from source audio. */
		REFRESHING,

		/** The generator is fully trained and ready to produce audio. */
		READY,

		/** The generator is currently producing audio samples. */
		GENERATING;

		/**
		 * Returns a human-readable display name for this state.
		 *
		 * @return the display name
		 */
		public String getName() {
			switch (this) {
				case NONE:
					return "None";
				case REFRESHING:
					return "Refreshing";
				case READY:
					return "Ready";
				case GENERATING:
					return "Generating";
				default:
					return "Unknown";
			}
		}
	}
}
