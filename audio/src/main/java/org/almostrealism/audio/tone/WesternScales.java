/*
 * Copyright 2021 Michael Murray
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

/**
 * Factory class for creating common Western musical scales.
 *
 * <p>WesternScales provides static factory methods for creating major and minor scales
 * using the standard Western interval patterns. Scales are created as {@link SetIntervalScale}
 * instances that can span multiple octaves.</p>
 *
 * <h2>Scale Intervals</h2>
 * <ul>
 *   <li><b>Major scale</b>: W-W-H-W-W-W-H (2-2-1-2-2-2-1 semitones)</li>
 *   <li><b>Minor scale</b>: W-H-W-W-H-W-W (2-1-2-2-1-2-2 semitones)</li>
 * </ul>
 * <p>Where W = whole step (2 semitones), H = half step (1 semitone)</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // C major scale (C-D-E-F-G-A-B), one octave
 * Scale<WesternChromatic> cMajor = WesternScales.major(WesternChromatic.C4, 1);
 *
 * // A minor scale (A-B-C-D-E-F-G), two octaves
 * Scale<WesternChromatic> aMinor = WesternScales.minor(WesternChromatic.A3, 2);
 *
 * // Iterate through scale notes
 * KeyboardTuning tuning = new DefaultKeyboardTuning();
 * cMajor.forEach(note -> {
 *     System.out.println(note + " = " + tuning.getTone(note).asHertz() + " Hz");
 * });
 * }</pre>
 *
 * @see Scale
 * @see SetIntervalScale
 * @see WesternChromatic
 */
public class WesternScales {
	private WesternScales() { }

	public static Scale<WesternChromatic> major(WesternChromatic root, int octaves) {
		return new SetIntervalScale<>(root, octaves, 2, 2, 1, 2, 2, 2, 1);
	}

	public static Scale<WesternChromatic> minor(WesternChromatic root, int octaves) {
		return new SetIntervalScale<>(root, octaves, 2, 1, 2, 2, 1, 2, 2);
	}
}
