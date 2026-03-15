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

package org.almostrealism.ml.midi.test;

import org.almostrealism.ml.midi.MidiFileReader;
import org.almostrealism.ml.midi.MidiNoteEvent;
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
