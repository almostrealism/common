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

import org.almostrealism.time.Frequency;

/**
 * Standard 12-tone equal temperament (12-TET) keyboard tuning.
 *
 * <p>DefaultKeyboardTuning implements the standard Western tuning system where
 * each octave is divided into 12 equal semitones, with A4 = 440 Hz by default.
 * The frequency ratio between adjacent semitones is 2^(1/12), approximately 1.0595.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Standard tuning (A4 = 440 Hz)
 * KeyboardTuning tuning = new DefaultKeyboardTuning();
 *
 * // Custom concert pitch (A4 = 432 Hz)
 * KeyboardTuning tuning432 = new DefaultKeyboardTuning(432);
 *
 * // Get frequencies
 * double a4 = tuning.getTone(WesternChromatic.A4).asHertz();  // 440.0
 * double c4 = tuning.getTone(WesternChromatic.C4).asHertz();  // ~261.63
 * }</pre>
 *
 * <h2>Key Range</h2>
 * <p>Supports 108 keys (9 octaves), covering the full piano range and beyond.
 * Key positions outside this range are clamped to the nearest valid key.</p>
 *
 * <h2>Frequency Formula</h2>
 * <p>For key position k (where A4 = position 48):</p>
 * <pre>frequency = A4_hz * 2^((k - 48) / 12)</pre>
 *
 * @see KeyboardTuning
 * @see WesternChromatic
 */
public class DefaultKeyboardTuning implements KeyboardTuning {
	private final Frequency[] freq;

	public DefaultKeyboardTuning() { this(440); }

	public DefaultKeyboardTuning(double a) {
		freq = new Frequency[108];
		for (int x = 0; x < 108; ++x) {
			freq[x] = new Frequency(a * Math.pow(2, (x - 48) / 12.0));
		}
	}

	@Override
	public Frequency getTone(int key, KeyNumbering numbering) {
		if (numbering == KeyNumbering.MIDI) key = key - 21;
		if (key < 0) key = 0;
		if (key >= freq.length) key = freq.length - 1;
		return freq[key];
	}
}
