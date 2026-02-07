/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.audio.health;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.optimize.HealthScore;

import java.util.ArrayList;
import java.util.List;

public class AudioHealthScore implements HealthScore {
	private long frames;
	private double score;
	private String output;
	private List<String> stems;
	private List<String> identifiers;

	private long generationTime;

	public AudioHealthScore() { this(0, 0.0, null, null, null); }

	public AudioHealthScore(long frames, double score) {
		this(frames, score, null, null, null);
	}

	public AudioHealthScore(long frames, double score, String output,
							List<String> stems, List<String> identifiers) {
		this.frames = frames;
		this.score = score;
		this.output = output;
		this.stems = new ArrayList<>();
		this.identifiers = new ArrayList<>();

		if (stems != null) {
			getStems().addAll(stems);
		}

		if (identifiers != null) {
			getIdentifiers().addAll(identifiers);
		}
	}

	public long getFrames() { return frames; }

	public void setFrames(long frames) { this.frames = frames; }

	public double getDuration() {
		return frames / (double) OutputLine.sampleRate;
	}

	@Override
	public double getScore() { return score; }

	public void setScore(double score) { this.score = score; }

	public String getOutput() { return output; }

	public void setOutput(String output) { this.output = output; }

	public List<String> getStems() { return stems; }
	public void setStems(List<String> stems) { this.stems = stems; }

	public List<String> getIdentifiers() { return identifiers; }
	public void setIdentifiers(List<String> identifiers) { this.identifiers = identifiers; }

	public long getGenerationTime() {
		return generationTime;
	}

	public void setGenerationTime(long generationTime) {
		this.generationTime = generationTime;
	}

	public List<String> getFiles() {
		ArrayList<String> files = new ArrayList<>();
		if (getOutput() != null) files.add(getOutput());
		if (getStems() != null) files.addAll(getStems());
		return files;
	}
}
