/*
 * Copyright 2023 Michael Murray
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

import org.almostrealism.music.notes.NoteSourceProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages a collection of audio {@link Generator} instances, wiring each generator
 * to a shared source provider and generation provider, and handling serialisation
 * of the generator list through a {@link Settings} record.
 */
public class GenerationManager {
	/** Source provider used to resolve audio sources for generators. */
	private final NoteSourceProvider sourceProvider;

	/** Generation provider (back-end service) used by all managed generators. */
	private final GenerationProvider generationProvider;

	/** The ordered list of managed generators. */
	private List<Generator> generators;

	/**
	 * Creates a generation manager.
	 *
	 * @param sourceProvider     the note source provider for resolving audio sources
	 * @param generationProvider the back-end generation provider
	 */
	public GenerationManager(NoteSourceProvider sourceProvider, GenerationProvider generationProvider) {
		this.sourceProvider = sourceProvider;
		this.generationProvider = generationProvider;
	}

	/**
	 * Creates a new generator with default settings, registers it, and returns it.
	 *
	 * @return the newly added generator
	 */
	public Generator addGenerator() {
		Generator g = new Generator();
		addGenerator(g);
		return g;
	}

	/**
	 * Registers an existing generator, wiring it to the shared providers.
	 *
	 * @param g the generator to register
	 */
	public void addGenerator(Generator g) {
		g.setSourceProvider(sourceProvider);
		g.setGenerationProvider(generationProvider);
		generators.add(g);
	}

	/** Returns the list of managed generators. */
	public List<Generator> getGenerators() { return generators; }

	/** Returns the back-end generation provider. */
	public GenerationProvider getGenerationProvider() { return generationProvider; }

	/**
	 * Returns a {@link Settings} snapshot capturing the current generator list.
	 *
	 * @return serialisable settings containing all current generators
	 */
	public Settings getSettings() {
		Settings settings = new Settings();
		settings.getGenerators().addAll(generators);
		return settings;
	}

	/**
	 * Restores the generator list from a {@link Settings} snapshot, re-wiring each
	 * generator to the shared providers.
	 *
	 * @param settings the settings to restore from
	 */
	public void setSettings(Settings settings) {
		generators = new ArrayList<>();
		if (settings.getGenerators() != null) generators.addAll(settings.getGenerators());
		generators.forEach(g -> g.setSourceProvider(sourceProvider));
		generators.forEach(g -> g.setGenerationProvider(generationProvider));
	}

	/** Serialisable settings record for {@link GenerationManager}. */
	public static class Settings {
		/** The list of generators to persist. */
		private List<Generator> generators = new ArrayList<>();

		/** Returns the persisted generators. */
		public List<Generator> getGenerators() { return generators; }

		/**
		 * Sets the persisted generators.
		 *
		 * @param generators list of generators to store
		 */
		public void setGenerators(List<Generator> generators) { this.generators = generators; }
	}
}
