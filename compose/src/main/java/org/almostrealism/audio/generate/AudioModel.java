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

package org.almostrealism.audio.generate;

import org.almostrealism.util.KeyUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

public class AudioModel {
	public static int DIM = 8;

	private String id;
	private String name;
	private double duration;
	private boolean pattern;
	private int embedDimensions;
	private long seed;
	private List<String> textConditions;
	private List<String> audioConditions;
	private Double creativity;

	public AudioModel() {
		this(null);
	}

	public AudioModel(String name) {
		this(name, new ArrayList<>());
	}

	public AudioModel(String name, List<String> textConditions) {
		setId(KeyUtils.generateKey());
		setName(name);
		setDuration(1);
		setPattern(false);
		setEmbedDimensions(DIM);
		setSeed(new Random().nextLong());
		setTextConditions(textConditions);
	}

	public String getId() { return id; }
	public void setId(String id) { this.id = id; }

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public double getDuration() { return duration; }
	public void setDuration(double duration) { this.duration = duration; }

	public boolean isPattern() { return pattern; }
	public void setPattern(boolean pattern) { this.pattern = pattern; }

	public int getEmbedDimensions() { return embedDimensions; }
	public void setEmbedDimensions(int embedDimensions) {
		this.embedDimensions = embedDimensions;
	}

	public long getSeed() { return seed; }
	public void setSeed(long seed) {
		this.seed = seed;
	}

	public List<String> getTextConditions() { return textConditions; }
	public void setTextConditions(List<String> textConditions) { this.textConditions = textConditions; }

	public List<String> getAudioConditions() { return audioConditions; }
	public void setAudioConditions(List<String> audioConditions) {
		this.audioConditions = audioConditions;
	}

	public Double getCreativity() { return creativity; }
	public void setCreativity(Double creativity) {
		this.creativity = creativity;
	}

	public void addAudioCondition(String condition) {
		if (audioConditions == null) audioConditions = new ArrayList<>();
		if (audioConditions.contains(condition)) return;
		audioConditions.add(condition);
	}

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
