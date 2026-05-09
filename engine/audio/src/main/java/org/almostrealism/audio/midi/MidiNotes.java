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

/**
 * Static helpers for working with MIDI note numbers.
 */
public final class MidiNotes {
	/** Pitch-class names indexed by {@code midi % 12}, sharps preferred. */
	private static final String[] NOTE_NAMES =
			{"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

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
}
