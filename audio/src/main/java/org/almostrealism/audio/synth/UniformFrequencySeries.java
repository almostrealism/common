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

package org.almostrealism.audio.synth;

import org.almostrealism.audio.tone.RelativeFrequencySet;
import org.almostrealism.time.Frequency;

import java.util.Arrays;

public class UniformFrequencySeries implements RelativeFrequencySet {
	private final double[] frequencies;

	public UniformFrequencySeries(double start, double length, int count) {
		frequencies = new double[count];
		double step = length / count;
		for (int i = 0; i < count; i++) {
			frequencies[i] = start + (i * step);
		}
	}

	public UniformFrequencySeries(double[] frequencyRatios) {
		this.frequencies = frequencyRatios;
	}

	@Override
	public Iterable<Frequency> getFrequencies(Frequency fundamental) {
		return Arrays.stream(frequencies)
				.mapToObj(f -> new Frequency(fundamental.asHertz() * f))
				.toList();
	}

	@Override
	public int count() { return frequencies.length; }
}
