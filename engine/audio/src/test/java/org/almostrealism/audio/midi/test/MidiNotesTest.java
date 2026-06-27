/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio.midi.test;

import org.almostrealism.audio.midi.MidiNotes;
import org.junit.Test;

import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link MidiNotes#parseNoteName(String)} — the inverse of
 * {@link MidiNotes#noteName(int)}.
 */
public class MidiNotesTest {

	/** Spot-checks representative notes across the MIDI range. */
	@Test
	public void parsesKnownNotes() {
		assertEquals(OptionalInt.of(60), MidiNotes.parseNoteName("C4"));
		assertEquals(OptionalInt.of(61), MidiNotes.parseNoteName("C#4"));
		assertEquals(OptionalInt.of(21), MidiNotes.parseNoteName("A0"));
		assertEquals(OptionalInt.of(108), MidiNotes.parseNoteName("C8"));
		assertEquals(OptionalInt.of(0), MidiNotes.parseNoteName("C-1"));
		assertEquals(OptionalInt.of(127), MidiNotes.parseNoteName("G9"));
	}

	/** parseNoteName must round-trip every MIDI note that noteName emits. */
	@Test
	public void roundTripsEveryMidiNote() {
		for (int midi = MidiNotes.MIN_NOTE; midi <= MidiNotes.MAX_NOTE; midi++) {
			String name = MidiNotes.noteName(midi);
			OptionalInt parsed = MidiNotes.parseNoteName(name);
			assertTrue("noteName(" + midi + ")=\"" + name + "\" should parse back",
					parsed.isPresent());
			assertEquals("Round trip for \"" + name + "\"", midi, parsed.getAsInt());
		}
	}

	/** Leading and trailing whitespace must not defeat the parse. */
	@Test
	public void toleratesSurroundingWhitespace() {
		assertEquals(OptionalInt.of(60), MidiNotes.parseNoteName("  C4 "));
	}

	/** Null, blank, mis-spelled, and out-of-range inputs return empty. */
	@Test
	public void rejectsMalformedOrOutOfRange() {
		assertFalse(MidiNotes.parseNoteName(null).isPresent());
		assertFalse(MidiNotes.parseNoteName("").isPresent());
		assertFalse(MidiNotes.parseNoteName("   ").isPresent());
		assertFalse("WesternChromatic 'S' spelling is not the noteName form",
				MidiNotes.parseNoteName("CS4").isPresent());
		assertFalse("Lower-case pitch class is not emitted by noteName",
				MidiNotes.parseNoteName("c4").isPresent());
		assertFalse("No octave", MidiNotes.parseNoteName("C").isPresent());
		assertFalse("Not a note", MidiNotes.parseNoteName("Plugin").isPresent());
		assertFalse("Below MIDI range", MidiNotes.parseNoteName("C-2").isPresent());
		assertFalse("Above MIDI range (G#9 = 128)",
				MidiNotes.parseNoteName("G#9").isPresent());
	}
}
