/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.audio.pattern;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.ParameterSet;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PatternLayerSeeds implements ConsoleFeatures {

	private double position;
	private double granularity;
	private final double minScale;
	private final double maxScale;
	private double bias;

	private final NoteAudioChoice choice;
	private final ParameterSet params;

	public PatternLayerSeeds() {
		this(0, 1.0, 0.0625, 64.0, 0.0, null, null);
	}

	public PatternLayerSeeds(double position, double granularity, double minScale, double maxScale,
							 double bias, NoteAudioChoice choice, ParameterSet params) {
		this.position = position;
		this.granularity = granularity;
		this.minScale = minScale;
		this.maxScale = maxScale;
		this.bias = bias;
		this.choice = choice;
		this.params = params;
	}

	public double getPosition() {
		return position;
	}
	public void setPosition(double position) {
		this.position = position;
	}

	public double getScale(double duration) {
		return Math.min(maxScale, Math.max(minScale, duration * granularity));
	}

	public double getScale(double duration, double min) {
		return Math.max(min, getScale(duration));
	}

	public double getGranularity() {
		return granularity;
	}
	public void setGranularity(double granularity) {
		this.granularity = granularity;
	}

	public double getBias() { return bias; }
	public void setBias(double bias) { this.bias = bias; }

	public Stream<PatternLayer> generator(PatternElementFactory factory, double offset, double duration,
										  ScaleTraversalStrategy scaleTraversalStrategy,
										  int scaleTraversalDepth, double minScale) {
		double g = getScale(duration, minScale);
		double count = Math.max(1.0, duration / g) + 1;

		List<PatternLayer> layers = IntStream.range(0, (int) count)
				.mapToObj(i ->
						factory.apply(null, position + offset + i * g, g,
								this.bias, scaleTraversalStrategy, scaleTraversalDepth,
								false, choice.isMelodic(), params).orElse(null))
				.filter(Objects::nonNull)
				.map(List::of)
				.map(elements -> new PatternLayer(choice, elements))
				.collect(Collectors.toList());

		if (layers.size() <= 0 && (this.bias) >= 1.0) {
			warn("No seeds generated, despite bias >= 1.0");
		}

		return layers.stream();
	}

	@Override
	public Console console() { return CellFeatures.console; }
}
