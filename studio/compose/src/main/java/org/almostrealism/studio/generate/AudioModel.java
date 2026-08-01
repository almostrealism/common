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

package org.almostrealism.studio.generate;

import org.almostrealism.util.KeyUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

/**
 * Descriptor for an ML-based audio generation model, capturing the conditioning inputs
 * (text and audio references), generation parameters (duration, seed, creativity), and
 * the latent embedding dimensionality.
 */
public class AudioModel {
	/** Default latent embedding dimension used when none is specified. */
	public static int DIM = 8;

	/** Unique identifier for this model descriptor. */
	private String id;

	/** Human-readable name for this model. */
	private String name;

	/** Target generation duration in seconds. */
	private double duration;

	/** When {@code true}, the model generates loopable pattern audio. */
	private boolean pattern;

	/** Number of dimensions in the latent embedding space. */
	private int embedDimensions;

	/** Random seed used for reproducible generation. */
	private long seed;

	/** Text prompt conditions guiding the audio generation. */
	private List<String> textConditions;

	/** Audio reference file paths used as conditioning inputs. */
	private List<String> audioConditions;

	/** Generation temperature controlling output diversity, or {@code null} for default. */
	private Double creativity;

	/** Creates a model with no name and a generated ID. */
	public AudioModel() {
		this(null);
	}

	/**
	 * Creates a model with the given name and an empty text-condition list.
	 *
	 * @param name the model name
	 */
	public AudioModel(String name) {
		this(name, new ArrayList<>());
	}

	/**
	 * Creates a fully specified model.
	 *
	 * @param name           the model name
	 * @param textConditions initial list of text prompt conditions
	 */
	public AudioModel(String name, List<String> textConditions) {
		setId(KeyUtils.generateKey());
		setName(name);
		setDuration(1);
		setPattern(false);
		setEmbedDimensions(DIM);
		setSeed(new Random().nextLong());
		setTextConditions(textConditions);
	}

	/** Returns the unique identifier. */
	public String getId() { return id; }

	/** Sets the unique identifier. */
	public void setId(String id) { this.id = id; }

	/** Returns the human-readable model name. */
	public String getName() { return name; }

	/** Sets the human-readable model name. */
	public void setName(String name) { this.name = name; }

	/** Returns the target generation duration in seconds. */
	public double getDuration() { return duration; }

	/** Sets the target generation duration in seconds. */
	public void setDuration(double duration) { this.duration = duration; }

	/** Returns {@code true} if this model generates loopable pattern audio. */
	public boolean isPattern() { return pattern; }

	/** Sets whether this model generates loopable pattern audio. */
	public void setPattern(boolean pattern) { this.pattern = pattern; }

	/** Returns the number of latent embedding dimensions. */
	public int getEmbedDimensions() { return embedDimensions; }

	/**
	 * Sets the number of latent embedding dimensions.
	 *
	 * @param embedDimensions the embedding dimension count
	 */
	public void setEmbedDimensions(int embedDimensions) {
		this.embedDimensions = embedDimensions;
	}

	/** Returns the random seed used for reproducible generation. */
	public long getSeed() { return seed; }

	/**
	 * Sets the random seed for reproducible generation.
	 *
	 * @param seed the seed value
	 */
	public void setSeed(long seed) {
		this.seed = seed;
	}

	/** Returns the list of text prompt conditions. */
	public List<String> getTextConditions() { return textConditions; }

	/**
	 * Sets the list of text prompt conditions.
	 *
	 * @param textConditions the text conditions
	 */
	public void setTextConditions(List<String> textConditions) { this.textConditions = textConditions; }

	/** Returns the list of audio reference file paths used as conditions. */
	public List<String> getAudioConditions() { return audioConditions; }

	/**
	 * Sets the list of audio reference file paths used as conditions.
	 *
	 * @param audioConditions the audio condition paths
	 */
	public void setAudioConditions(List<String> audioConditions) {
		this.audioConditions = audioConditions;
	}

	/** Returns the generation temperature, or {@code null} to use the model default. */
	public Double getCreativity() { return creativity; }

	/**
	 * Sets the generation temperature.
	 *
	 * @param creativity the temperature value, or {@code null} for the default
	 */
	public void setCreativity(Double creativity) {
		this.creativity = creativity;
	}

	/**
	 * Adds an audio condition file path if not already present.
	 *
	 * @param condition the path to the audio condition file
	 */
	public void addAudioCondition(String condition) {
		if (audioConditions == null) audioConditions = new ArrayList<>();
		if (audioConditions.contains(condition)) return;
		audioConditions.add(condition);
	}

	/**
	 * Returns a human-readable summary of all conditioning inputs.
	 *
	 * @param audioDescription function mapping an audio condition path to a display string
	 * @return comma-separated summary of text and audio conditions
	 */
	public String conditionSummary(UnaryOperator<String> audioDescription) {
		StringBuilder builder = new StringBuilder();

		if (textConditions != null && !textConditions.isEmpty()) {
			builder.append(String.join(", ", textConditions));
		}

		if (audioConditions != null && !audioConditions.isEmpty()) {
			if (builder.length() > 0) {
				builder.append(" + ");
			}

			builder.append(String.join(", ",
					audioConditions.stream().map(audioDescription).toList()));
		}

		return builder.toString();
	}
}
