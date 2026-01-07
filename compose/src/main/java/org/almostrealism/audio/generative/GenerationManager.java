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

package org.almostrealism.audio.generative;

import org.almostrealism.audio.notes.NoteSourceProvider;

import java.util.ArrayList;
import java.util.List;

public class GenerationManager {
	private final NoteSourceProvider sourceProvider;
	private final GenerationProvider generationProvider;
	private List<Generator> generators;

	public GenerationManager(NoteSourceProvider sourceProvider, GenerationProvider generationProvider) {
		this.sourceProvider = sourceProvider;
		this.generationProvider = generationProvider;
	}

	public Generator addGenerator() {
		Generator g = new Generator();
		addGenerator(g);
		return g;
	}

	public void addGenerator(Generator g) {
		g.setSourceProvider(sourceProvider);
		g.setGenerationProvider(generationProvider);
		generators.add(g);
	}

	public List<Generator> getGenerators() { return generators; }

	public GenerationProvider getGenerationProvider() { return generationProvider; }

	public Settings getSettings() {
		Settings settings = new Settings();
		settings.getGenerators().addAll(generators);
		return settings;
	}

	public void setSettings(Settings settings) {
		generators = new ArrayList<>();
		if (settings.getGenerators() != null) generators.addAll(settings.getGenerators());
		generators.forEach(g -> g.setSourceProvider(sourceProvider));
		generators.forEach(g -> g.setGenerationProvider(generationProvider));
	}

	public static class Settings {
		private List<Generator> generators = new ArrayList<>();

		public List<Generator> getGenerators() { return generators; }
		public void setGenerators(List<Generator> generators) { this.generators = generators; }
	}
}
