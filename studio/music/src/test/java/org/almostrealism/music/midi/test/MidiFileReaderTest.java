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

package org.almostrealism.music.midi.test;

import org.almostrealism.music.midi.MidiFileReader;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link MidiFileReader} MIDI file write and read operations.
 * Verifies round-trip fidelity, multi-instrument channel mapping,
 * drum channel handling, and edge cases.
 */
public class MidiFileReaderTest extends TestSuiteBase {

	/**
	 * Verify that writing note events to a MIDI file and reading them
	 * back preserves pitch, onset, duration, velocity, and instrument.
	 */
	@Test
	public void testWriteAndReadRoundTrip() throws Exception {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 50, 80, 0));
		events.add(new MidiNoteEvent(64, 100, 50, 90, 0));
		events.add(new MidiNoteEvent(67, 200, 100, 100, 0));

		MidiFileReader reader = new MidiFileReader();
		File tempFile = File.createTempFile("midi-roundtrip-", ".mid");
		tempFile.deleteOnExit();

		reader.write(events, tempFile);
		List<MidiNoteEvent> readBack = reader.read(tempFile);

		assertEquals("Event count", events.size(), readBack.size());
		for (int i = 0; i < events.size(); i++) {
			MidiNoteEvent expected = events.get(i);
			MidiNoteEvent actual = readBack.get(i);
			assertEquals("Pitch at " + i, expected.getPitch(), actual.getPitch());
			assertEquals("Onset at " + i, expected.getOnset(), actual.getOnset());
			assertEquals("Duration at " + i, expected.getDuration(), actual.getDuration());
			assertEquals("Velocity at " + i, expected.getVelocity(), actual.getVelocity());
			assertEquals("Instrument at " + i, expected.getInstrument(), actual.getInstrument());
		}
	}

	/**
	 * Verify that notes with different instruments are written to separate
	 * MIDI channels and read back with the correct instrument assignments.
	 */
	@Test
	public void testMultipleInstruments() throws Exception {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 50, 80, 0));
		events.add(new MidiNoteEvent(64, 100, 50, 90, 24));
		events.add(new MidiNoteEvent(48, 200, 100, 100, 32));

		MidiFileReader reader = new MidiFileReader();
		File tempFile = File.createTempFile("midi-multi-inst-", ".mid");
		tempFile.deleteOnExit();

		reader.write(events, tempFile);
		List<MidiNoteEvent> readBack = reader.read(tempFile);

		assertEquals("Event count", events.size(), readBack.size());
		for (int i = 0; i < events.size(); i++) {
			assertEquals("Pitch at " + i,
					events.get(i).getPitch(), readBack.get(i).getPitch());
			assertEquals("Instrument at " + i,
					events.get(i).getInstrument(), readBack.get(i).getInstrument());
		}
	}

	/**
	 * Verify that drum events (instrument 128) are written to MIDI channel 9
	 * and mixed correctly with non-drum events.
	 */
	@Test
	public void testDrumChannel() throws Exception {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 100, 80, 0));
		events.add(new MidiNoteEvent(36, 100, 50, 100, 128));
		events.add(new MidiNoteEvent(38, 200, 50, 100, 128));

		MidiFileReader reader = new MidiFileReader();
		File tempFile = File.createTempFile("midi-drums-", ".mid");
		tempFile.deleteOnExit();

		reader.write(events, tempFile);
		List<MidiNoteEvent> readBack = reader.read(tempFile);

		assertEquals("Event count", events.size(), readBack.size());
		for (int i = 0; i < events.size(); i++) {
			assertEquals("Pitch at " + i,
					events.get(i).getPitch(), readBack.get(i).getPitch());
			assertEquals("Instrument at " + i,
					events.get(i).getInstrument(), readBack.get(i).getInstrument());
		}
	}

	/**
	 * Verify that writing and reading an empty event list produces no events.
	 */
	@Test
	public void testEmptyEventList() throws Exception {
		MidiFileReader reader = new MidiFileReader();
		File tempFile = File.createTempFile("midi-empty-", ".mid");
		tempFile.deleteOnExit();

		reader.write(new ArrayList<>(), tempFile);
		List<MidiNoteEvent> readBack = reader.read(tempFile);

		assertEquals("Empty file should produce no events", 0, readBack.size());
	}

	/**
	 * Verify that boundary MIDI values (pitch 0 and 127, velocity 1 and 127)
	 * survive a write/read round-trip.
	 */
	@Test
	public void testBoundaryMidiValues() throws Exception {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(0, 0, 1, 1, 0));
		events.add(new MidiNoteEvent(127, 100, 50, 127, 0));

		MidiFileReader reader = new MidiFileReader();
		File tempFile = File.createTempFile("midi-boundary-", ".mid");
		tempFile.deleteOnExit();

		reader.write(events, tempFile);
		List<MidiNoteEvent> readBack = reader.read(tempFile);

		assertEquals("Event count", 2, readBack.size());
		assertEquals("Min pitch", 0, readBack.get(0).getPitch());
		assertEquals("Min velocity", 1, readBack.get(0).getVelocity());
		assertEquals("Max pitch", 127, readBack.get(1).getPitch());
		assertEquals("Max velocity", 127, readBack.get(1).getVelocity());
	}

	/**
	 * Verify that more than 15 distinct non-drum instruments are handled
	 * without error. Overflow instruments fall back to channel 0, which
	 * means their PROGRAM_CHANGE overwrites channel 0's original program.
	 * Instruments 1-14 (on dedicated channels 1-8, 10-15) should round-trip.
	 * All notes should still be present regardless of instrument mapping.
	 */
	@Test
	public void testChannelOverflow() throws Exception {
		List<MidiNoteEvent> events = new ArrayList<>();
		for (int inst = 0; inst < 20; inst++) {
			events.add(new MidiNoteEvent(60, (long) inst * 100, 50, 80, inst));
		}

		MidiFileReader reader = new MidiFileReader();
		File tempFile = File.createTempFile("midi-overflow-", ".mid");
		tempFile.deleteOnExit();

		reader.write(events, tempFile);
		List<MidiNoteEvent> readBack = reader.read(tempFile);

		assertEquals("All notes should survive channel overflow",
				20, readBack.size());

		// Instruments 1-14 are on dedicated channels and should round-trip.
		// Instrument 0 shares channel 0 with overflow instruments (15-19),
		// so its PROGRAM_CHANGE gets overwritten by the last overflow instrument.
		for (int i = 1; i < 15; i++) {
			assertEquals("Instrument " + i + " within channel limit should round-trip",
					events.get(i).getInstrument(), readBack.get(i).getInstrument());
		}

		// All pitches and onsets should still be correct regardless
		for (int i = 0; i < 20; i++) {
			assertEquals("Pitch at " + i, events.get(i).getPitch(), readBack.get(i).getPitch());
			assertEquals("Onset at " + i, events.get(i).getOnset(), readBack.get(i).getOnset());
		}
	}

	/**
	 * Verify that simultaneous notes (same onset time) are handled correctly.
	 */
	@Test
	public void testSimultaneousNotes() throws Exception {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 100, 80, 0));
		events.add(new MidiNoteEvent(64, 0, 100, 80, 0));
		events.add(new MidiNoteEvent(67, 0, 100, 80, 0));

		MidiFileReader reader = new MidiFileReader();
		File tempFile = File.createTempFile("midi-chord-", ".mid");
		tempFile.deleteOnExit();

		reader.write(events, tempFile);
		List<MidiNoteEvent> readBack = reader.read(tempFile);

		assertEquals("Chord event count", 3, readBack.size());
		for (int i = 0; i < events.size(); i++) {
			assertEquals("Onset at " + i, 0L, readBack.get(i).getOnset());
		}
	}
}
