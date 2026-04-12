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

package org.almostrealism.studio.health;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.optimize.HealthScore;

import java.util.ArrayList;
import java.util.List;

/**
 * Health score for an audio generation cycle, capturing the number of rendered
 * frames, the aggregate quality score, and paths to output and stem files.
 */
public class AudioHealthScore implements HealthScore {
	/** Total number of audio frames rendered during the health evaluation. */
	private long frames;

	/** Aggregate quality score in the range [0.0, 1.0]. */
	private double score;

	/** Path to the primary output audio file, or {@code null} if not written. */
	private String output;

	/** Paths to individual stem audio files. */
	private List<String> stems;

	/** Identifiers associated with the audio generation (e.g. pattern or genome IDs). */
	private List<String> identifiers;

	/** Wall-clock time in milliseconds taken to produce the audio. */
	private long generationTime;

	/** Creates an empty health score with zero frames and zero score. */
	public AudioHealthScore() { this(0, 0.0, null, null, null); }

	/**
	 * Creates a health score with the given frame count and quality score.
	 *
	 * @param frames the number of audio frames rendered
	 * @param score  the quality score in [0.0, 1.0]
	 */
	public AudioHealthScore(long frames, double score) {
		this(frames, score, null, null, null);
	}

	/**
	 * Creates a fully specified health score.
	 *
	 * @param frames      the number of audio frames rendered
	 * @param score       the quality score in [0.0, 1.0]
	 * @param output      path to the primary output file, or {@code null}
	 * @param stems       paths to stem files, or {@code null}
	 * @param identifiers associated identifiers, or {@code null}
	 */
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

	/** Returns the number of audio frames rendered. */
	public long getFrames() { return frames; }

	/** Sets the number of audio frames rendered. */
	public void setFrames(long frames) { this.frames = frames; }

	/**
	 * Returns the duration of the rendered audio in seconds, derived from the
	 * frame count divided by the sample rate.
	 */
	public double getDuration() {
		return frames / (double) OutputLine.sampleRate;
	}

	/** {@inheritDoc} */
	@Override
	public double getScore() { return score; }

	/** Sets the quality score. */
	public void setScore(double score) { this.score = score; }

	/** Returns the path to the primary output audio file, or {@code null} if not written. */
	public String getOutput() { return output; }

	/** Sets the path to the primary output audio file. */
	public void setOutput(String output) { this.output = output; }

	/** Returns the list of stem file paths. */
	public List<String> getStems() { return stems; }

	/** Sets the list of stem file paths. */
	public void setStems(List<String> stems) { this.stems = stems; }

	/** Returns the list of identifiers associated with this generation. */
	public List<String> getIdentifiers() { return identifiers; }

	/** Sets the list of identifiers associated with this generation. */
	public void setIdentifiers(List<String> identifiers) { this.identifiers = identifiers; }

	/** Returns the wall-clock generation time in milliseconds. */
	public long getGenerationTime() {
		return generationTime;
	}

	/** Sets the wall-clock generation time in milliseconds. */
	public void setGenerationTime(long generationTime) {
		this.generationTime = generationTime;
	}

	/**
	 * Returns a combined list of all output file paths, including the primary output
	 * and all stem files.
	 */
	public List<String> getFiles() {
		ArrayList<String> files = new ArrayList<>();
		if (getOutput() != null) files.add(getOutput());
		if (getStems() != null) files.addAll(getStems());
		return files;
	}
}
