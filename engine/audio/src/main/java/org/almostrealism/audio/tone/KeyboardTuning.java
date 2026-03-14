/*
 * Copyright 2022 Michael Murray
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

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Interface for keyboard tuning systems that map key positions to frequencies.
 *
 * <p>KeyboardTuning provides the abstraction for different tuning systems (equal temperament,
 * just intonation, etc.). The default implementation {@link DefaultKeyboardTuning} uses
 * standard 12-tone equal temperament (12-TET) with A4 = 440 Hz.</p>
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * KeyboardTuning tuning = new DefaultKeyboardTuning();
 *
 * // Get frequency for A4
 * Frequency a4 = tuning.getTone(WesternChromatic.A4);
 * double hz = a4.asHertz();  // 440.0
 *
 * // Get frequencies for a scale
 * Scale<WesternChromatic> scale = WesternScales.major(WesternChromatic.C4, 1);
 * List<Frequency> frequencies = tuning.getTones(scale);
 * }</pre>
 *
 * <h2>Key Numbering</h2>
 * <p>Keys can be specified using different numbering systems via {@link KeyNumbering}:</p>
 * <ul>
 *   <li>{@link KeyNumbering#STANDARD} - Piano key numbering (A0 = 0)</li>
 *   <li>{@link KeyNumbering#MIDI} - MIDI note numbering (A0 = 21)</li>
 * </ul>
 *
 * @see DefaultKeyboardTuning
 * @see KeyPosition
 * @see Scale
 */
public interface KeyboardTuning {
	default Frequency getTone(KeyPosition pos) {
		if (pos.position() < 0) {
			// Frequency ratios computed against this
			// key position should simply be unaltered
			return new Frequency(1.0);
		}

		return getTone(pos.position(), KeyNumbering.STANDARD);
	}

	default <T extends KeyPosition> List<Frequency> getTones(Scale<T> scale) {
		return IntStream.range(0, scale.length())
				.mapToObj(scale::valueAt)
				.map(this::getTone)
				.collect(Collectors.toList());
	}

	Frequency getTone(int key, KeyNumbering numbering);

	default Frequency getRelativeFrequency(KeyPosition<?> root, KeyPosition<?> target) {
		return new Frequency(target == null ? 1.0 :
				(getTone(target).asHertz() / getTone(root).asHertz()));
	}
}
