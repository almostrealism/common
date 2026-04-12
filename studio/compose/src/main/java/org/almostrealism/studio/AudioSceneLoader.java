/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.music.pattern.ChordProgressionManager;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.studio.generative.GenerationManager;
import org.almostrealism.space.Animation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;

/**
 * Utility class for loading and saving {@link AudioScene} configurations.
 * Provides JSON serialization support via {@link #defaultMapper()}, convenience
 * factory methods for creating scenes from files, and the {@link Settings} data class.
 */
public class AudioSceneLoader {

	/** Not instantiable. */
	private AudioSceneLoader() {}

	/**
	 * Convenience factory method that creates and fully configures an audio scene from files.
	 *
	 * @param settingsFile path to the JSON settings file, or {@code null} for defaults
	 * @param patternsFile path to the JSON patterns file
	 * @param libraryRoot  path to the root sample library directory, or {@code null}
	 * @param bpm          the initial tempo in beats per minute
	 * @param sampleRate   the audio sample rate in Hz
	 * @return a fully configured {@link AudioScene}
	 * @throws IOException if any file cannot be read
	 */
	public static AudioScene<?> load(String settingsFile, String patternsFile, String libraryRoot, double bpm, int sampleRate) throws IOException {
		return load(null, settingsFile, patternsFile, libraryRoot, bpm, sampleRate);
	}

	/**
	 * Factory method that creates a visually backed audio scene from files.
	 *
	 * @param scene        the optional visual scene; may be {@code null}
	 * @param settingsFile path to the JSON settings file, or {@code null} for defaults
	 * @param patternsFile path to the JSON patterns file
	 * @param libraryRoot  path to the root sample library directory, or {@code null}
	 * @param bpm          the initial tempo in beats per minute
	 * @param sampleRate   the audio sample rate in Hz
	 * @return a fully configured {@link AudioScene}
	 * @throws IOException if any file cannot be read
	 */
	public static AudioScene<?> load(Animation<?> scene, String settingsFile, String patternsFile, String libraryRoot, double bpm, int sampleRate) throws IOException {
		AudioScene<?> audioScene = new AudioScene<>(scene, bpm, sampleRate);
		audioScene.loadPatterns(patternsFile);
		audioScene.setTuning(new DefaultKeyboardTuning());
		audioScene.loadSettings(settingsFile == null ? null : new File(settingsFile));
		if (libraryRoot != null) audioScene.setLibraryRoot(new FileWaveDataProviderNode(new File(libraryRoot)));
		return audioScene;
	}

	/**
	 * Creates a configured {@link ObjectMapper} for JSON serialization and deserialization of
	 * scene settings. Includes custom deserializers for {@link KeyPosition} and
	 * {@link WesternChromatic}.
	 *
	 * @return a configured {@link ObjectMapper}
	 */
	public static ObjectMapper defaultMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		SimpleModule module = new SimpleModule();
		module.addDeserializer(KeyPosition.class, keyPositionDeserializer(KeyPosition.class, KeyPosition::of));
		module.addDeserializer(WesternChromatic.class, keyPositionDeserializer(WesternChromatic.class, s -> WesternChromatic.valueOf(s)));
		mapper.registerModule(module);

		return mapper;
	}

	/**
	 * Creates a JSON deserializer for key position types that handles both plain string
	 * values and legacy array-encoded formats produced by older serializers.
	 *
	 * @param <T>     the key position type
	 * @param clazz   the target type class
	 * @param factory a factory function that creates instances from string representations
	 * @return a {@link StdDeserializer} for the given key position type
	 */
	private static <T> StdDeserializer<T> keyPositionDeserializer(Class<T> clazz, Function<String, T> factory) {
		return new StdDeserializer<>(clazz) {
			@Override
			public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
				if (p.currentToken() == JsonToken.START_ARRAY) {
					p.nextToken(); // class name
					p.nextToken(); // value
					String value = p.getValueAsString();
					p.nextToken(); // END_ARRAY
					return factory.apply(value);
				}
				return factory.apply(p.getValueAsString());
			}
		};
	}

	/**
	 * Serializable settings for an {@link AudioScene}. Captures tempo, structure,
	 * library path, chord progression, pattern system, channel names, and effects routing.
	 * Used with {@link AudioScene#getSettings()} and {@link AudioScene#setSettings(AudioScene.Settings)}.
	 */
	public static class Settings {
		/** Tempo in beats per minute. */
		private double bpm = 120;

		/** Number of beats per measure. */
		private int measureSize = 4;

		/** Total number of measures in the composition. */
		private int totalMeasures = 64;

		/** Measure indices at which global clock resets (breaks) occur. */
		private List<Integer> breaks = new ArrayList<>();

		/** Structural sections defining regions of the composition. */
		private List<Section> sections = new ArrayList<>();

		/** Root path of the audio sample library; may be {@code null}. */
		private String libraryRoot;

		/** Chord progression settings. */
		private ChordProgressionManager.Settings chordProgression;

		/** Pattern system settings controlling note scheduling and layers. */
		private PatternSystemManager.Settings patternSystem;

		/** Human-readable channel names. */
		private List<String> channelNames;

		/** Channel indices routed through the wet (effects) bus. */
		private List<Integer> wetChannels;

		/** Channel indices routed through the reverb bus. */
		private List<Integer> reverbChannels;

		/** Generation manager settings for ML-based audio generation. */
		private GenerationManager.Settings generation;

		/** Creates a new {@code Settings} instance with default pattern system and generation settings. */
		public Settings() {
			patternSystem = new PatternSystemManager.Settings();
			generation = new GenerationManager.Settings();
		}

		public double getBpm() { return bpm; }
		public void setBpm(double bpm) { this.bpm = bpm; }

		public int getMeasureSize() { return measureSize; }
		public void setMeasureSize(int measureSize) { this.measureSize = measureSize; }

		public int getTotalMeasures() { return totalMeasures; }
		public void setTotalMeasures(int totalMeasures) { this.totalMeasures = totalMeasures; }

		public List<Integer> getBreaks() { return breaks; }
		public void setBreaks(List<Integer> breaks) { this.breaks = breaks; }

		public List<Section> getSections() { return sections; }
		public void setSections(List<Section> sections) { this.sections = sections; }

		public String getLibraryRoot() { return libraryRoot; }
		public void setLibraryRoot(String libraryRoot) { this.libraryRoot = libraryRoot; }

		public ChordProgressionManager.Settings getChordProgression() { return chordProgression; }
		public void setChordProgression(ChordProgressionManager.Settings chordProgression) { this.chordProgression = chordProgression; }

		public PatternSystemManager.Settings getPatternSystem() { return patternSystem; }
		public void setPatternSystem(PatternSystemManager.Settings patternSystem) { this.patternSystem = patternSystem; }

		public List<String> getChannelNames() { return channelNames; }
		public void setChannelNames(List<String> channelNames) { this.channelNames = channelNames; }

		public List<Integer> getWetChannels() { return wetChannels; }
		public void setWetChannels(List<Integer> wetChannels) { this.wetChannels = wetChannels; }

		public List<Integer> getReverbChannels() { return reverbChannels; }
		public void setReverbChannels(List<Integer> reverbChannels) { this.reverbChannels = reverbChannels; }

		public GenerationManager.Settings getGeneration() { return generation; }
		public void setGeneration(GenerationManager.Settings generation) { this.generation = generation; }

		/**
		 * A named structural section of the composition, defined by a start measure
		 * position and a duration in measures.
		 */
		public static class Section {
			/** Starting measure index of this section. */
			private int position;

			/** Duration of this section in measures. */
			private int length;

			/** Creates an empty section with position 0 and length 0. */
			public Section() { }

			/**
			 * Creates a section at the given start position with the specified duration.
			 *
			 * @param position the starting measure index
			 * @param length   the section duration in measures
			 */
			public Section(int position, int length) {
				this.position = position;
				this.length = length;
			}

			/**
			 * Returns the starting measure index of this section.
			 *
			 * @return the start position in measures
			 */
			public int getPosition() { return position; }

			/**
			 * Sets the starting measure index of this section.
			 *
			 * @param position the new start position in measures
			 */
			public void setPosition(int position) { this.position = position; }

			/**
			 * Returns the duration of this section in measures.
			 *
			 * @return the section length in measures
			 */
			public int getLength() { return length; }

			/**
			 * Sets the duration of this section in measures.
			 *
			 * @param length the new section length in measures
			 */
			public void setLength(int length) { this.length = length; }
		}

		/**
		 * Creates a standard set of scene settings with default sections, breaks,
		 * chord progression, pattern system configuration, and standard channel routing.
		 *
		 * @param channels           the number of mix channels
		 * @param patternsPerChannel the number of patterns per channel
		 * @param activePatterns     operator returning the number of active patterns per channel
		 * @param layersPerPattern   operator returning the number of layers per channel
		 * @param minLayerScale      function returning the minimum layer scale per channel
		 * @param duration           operator returning the loop duration per channel
		 * @return a fully configured default {@link Settings} instance
		 */
		public static Settings defaultSettings(int channels, int patternsPerChannel,
											   IntUnaryOperator activePatterns,
											   IntUnaryOperator layersPerPattern,
											   IntToDoubleFunction minLayerScale,
											   IntUnaryOperator duration) {
			Settings settings = new Settings();
			settings.getSections().add(new Section(0, 16));
			settings.getSections().add(new Section(16, 16));
			settings.getSections().add(new Section(32, 8));
			settings.getBreaks().add(40);
			settings.getSections().add(new Section(40, 16));
			settings.getSections().add(new Section(56, 16));
			settings.getSections().add(new Section(72, 8));
			settings.getBreaks().add(80);
			settings.getSections().add(new Section(80, 64));
			settings.setTotalMeasures(144);
			settings.setChordProgression(ChordProgressionManager.Settings.defaultSettings());
			settings.setPatternSystem(PatternSystemManager.Settings
					.defaultSettings(channels, patternsPerChannel, activePatterns,
									layersPerPattern, minLayerScale, duration));
			settings.setChannelNames(List.of("Kick", "Drums", "Bass", "Harmony", "Lead", "Atmosphere"));
			settings.setWetChannels(List.of(2, 3, 4, 5));
			settings.setReverbChannels(List.of(1, 2, 3, 4, 5));
			return settings;
		}
	}
}
