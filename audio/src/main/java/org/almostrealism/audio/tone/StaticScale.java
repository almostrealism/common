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

import java.util.ArrayList;
import java.util.List;

/**
 * A static implementation of {@link Scale} backed by an explicit list of notes.
 *
 * <p>StaticScale stores a fixed collection of key positions representing a musical scale.
 * Unlike {@link SetIntervalScale}, which generates notes algorithmically from intervals,
 * StaticScale maintains an explicit list of notes that can be set at construction or
 * modified later.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a C major triad
 * StaticScale<WesternChromatic> triad = new StaticScale<>(
 *     WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);
 *
 * // Or use the factory method
 * Scale<WesternChromatic> pentatonic = Scale.of(
 *     WesternChromatic.C4, WesternChromatic.D4,
 *     WesternChromatic.E4, WesternChromatic.G4, WesternChromatic.A4);
 *
 * // Access notes by position
 * WesternChromatic root = triad.valueAt(0);  // C4
 * }</pre>
 *
 * <p>This class also supports deserialization from lists containing String note names,
 * which are automatically converted to {@link WesternChromatic} values.</p>
 *
 * @param <T> the type of key position (typically {@link WesternChromatic})
 * @see Scale
 * @see SetIntervalScale
 * @see WesternChromatic
 */
public class StaticScale<T extends KeyPosition> implements Scale<T> {
	private List<T> notes;

	public StaticScale() { }

	public StaticScale(T[] notes) { setNotes(List.of(notes)); }

	public List<T> getNotes() { return notes; }

	public void setNotes(List<T> notes) {
		this.notes = new ArrayList<>();

		List n = notes;
		for (int i = 0; i < n.size(); i++) {
			if (n.get(i) instanceof String) {
				this.notes.add((T) WesternChromatic.valueOf((String) n.get(i)));
			} else {
				this.notes.add(notes.get(i));
			}
		}
	}

	@Override
	public int length() {
		return notes.size();
	}

	@Override
	public T valueAt(int pos) {
		return notes.get(pos);
	}
}
