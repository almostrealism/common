/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio.midi;

import java.util.OptionalInt;

/**
 * Static helpers for working with MIDI note numbers.
 */
public final class MidiNotes {
	/** Pitch-class names indexed by {@code midi % 12}, sharps preferred. */
	private static final String[] NOTE_NAMES =
			{"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

	/** Lowest valid MIDI note number. */
	public static final int MIN_NOTE = 0;

	/** Highest valid MIDI note number. */
	public static final int MAX_NOTE = 127;

	/** Utility class — not instantiable. */
	private MidiNotes() { }

	/**
	 * Translates a MIDI note number (0-127) to its scientific-pitch name
	 * (e.g. 60 -&gt; "C4").
	 */
	public static String noteName(int midi) {
		int octave = (midi / 12) - 1;
		String pitch = NOTE_NAMES[Math.floorMod(midi, 12)];
		return pitch + octave;
	}

	/**
	 * Parses a scientific-pitch note name back to its MIDI note number —
	 * the exact inverse of {@link #noteName(int)}.
	 *
	 * <p>Accepts the same spelling {@link #noteName(int)} emits: an
	 * upper-case pitch class ({@code A}–{@code G}), an optional {@code '#'}
	 * for sharps, then a (possibly negative) octave number — e.g.
	 * {@code "C4"} -&gt; 60, {@code "C#4"} -&gt; 61, {@code "A0"} -&gt; 21,
	 * {@code "C-1"} -&gt; 0. Surrounding whitespace is ignored.</p>
	 *
	 * <p>Returns {@link OptionalInt#empty()} when {@code name} is null,
	 * blank, not a recognised note spelling, or resolves to a note outside
	 * the {@code 0..127} MIDI range. This is deliberately strict so callers
	 * can use a present result as a reliable signal.</p>
	 *
	 * @param name a scientific-pitch note name such as {@code "C#4"}.
	 * @return the MIDI note number, or empty when {@code name} is not a
	 *         valid in-range note spelling.
	 */
	public static OptionalInt parseNoteName(String name) {
		if (name == null) return OptionalInt.empty();

		String s = name.trim();
		if (s.isEmpty()) return OptionalInt.empty();

		int i = 1;
		if (i < s.length() && s.charAt(i) == '#') i++;

		String pitch = s.substring(0, i);
		int pitchClass = -1;
		for (int p = 0; p < NOTE_NAMES.length; p++) {
			if (NOTE_NAMES[p].equals(pitch)) {
				pitchClass = p;
				break;
			}
		}
		if (pitchClass < 0) return OptionalInt.empty();

		int octave;
		try {
			octave = Integer.parseInt(s.substring(i));
		} catch (NumberFormatException e) {
			return OptionalInt.empty();
		}

		int midi = (octave + 1) * 12 + pitchClass;
		if (midi < MIN_NOTE || midi > MAX_NOTE) return OptionalInt.empty();
		return OptionalInt.of(midi);
	}
}
