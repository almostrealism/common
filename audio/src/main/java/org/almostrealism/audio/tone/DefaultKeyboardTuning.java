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
