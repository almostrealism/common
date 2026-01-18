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

package org.almostrealism.audio.tone;

import org.almostrealism.time.Frequency;

import java.util.Iterator;

/**
 * A set of frequencies expressed as ratios relative to a fundamental frequency.
 *
 * <p>RelativeFrequencySet defines a collection of frequency ratios that can be
 * applied to any fundamental frequency to produce a set of absolute frequencies.
 * This is used for defining overtone series, chord structures, and other
 * harmonic relationships.</p>
 *
 * @see org.almostrealism.audio.synth.OvertoneSeries
 * @see org.almostrealism.audio.synth.UniformFrequencySeries
 */
public interface RelativeFrequencySet extends Iterable<Frequency> {
	@Override
	default Iterator<Frequency> iterator() {
		return getFrequencies(new Frequency(1.0)).iterator();
	}

	Iterable<Frequency> getFrequencies(Frequency fundamental);

	int count();
}
