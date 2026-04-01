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

/**
 * Represents a position on a musical keyboard or within a chromatic scale.
 *
 * <p>KeyPosition provides a self-referential interface for navigating musical notes,
 * allowing implementations to define their position within a scale and traverse to
 * adjacent notes. This is primarily implemented by {@link WesternChromatic} for the
 * 88-key piano keyboard.</p>
 *
 * <h2>Key Methods</h2>
 * <ul>
 *   <li>{@link #position()}: Returns the numeric position within the scale</li>
 *   <li>{@link #next()}: Returns the next position in the chromatic scale</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * KeyPosition<?> pos = KeyPosition.of("C4");
 * int position = pos.position();  // Get numeric position
 * KeyPosition<?> nextNote = pos.next();  // Move to C#4
 *
 * // None position for missing/invalid notes
 * KeyPosition<?> none = KeyPosition.none();
 * none.position();  // Returns -1
 * }</pre>
 *
 * @param <T> the concrete KeyPosition type, enabling type-safe navigation
 * @see WesternChromatic
 * @see Scale
 * @see KeyboardTuning
 */
public interface KeyPosition<T extends KeyPosition<T>> {
	/**
	 * Returns the numeric position of this note within its scale.
	 *
	 * @return the zero-based position index (or -1 for the none position)
	 */
	int position();

	/**
	 * Returns the next note in the chromatic scale after this one.
	 *
	 * @return the next KeyPosition
	 */
	T next();

	/**
	 * Returns a sentinel KeyPosition representing no note or an invalid position.
	 *
	 * @return a KeyPosition whose position() returns -1 and whose next() returns itself
	 */
	static KeyPosition<?> none() {
		return new KeyPosition() {
			@Override
			public int position() { return -1; }

			@Override
			public KeyPosition<?> next() {
				return this;
			}
		};
	}

	/**
	 * Returns the KeyPosition for the given note name using WesternChromatic naming.
	 *
	 * @param name the note name (e.g., "C4", "A4", "FS3")
	 * @return the corresponding WesternChromatic key position
	 * @throws IllegalArgumentException if the name does not match any known note
	 */
	static KeyPosition of(String name) {
		return WesternChromatic.valueOf(name);
	}
}
