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

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
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
	@Test(timeout = 60000)
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
	@Test(timeout = 60000)
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
	@Test(timeout = 60000)
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
	@Test(timeout = 60000)
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
	@Test(timeout = 60000)
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
	@Test(timeout = 60000)
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
	@Test(timeout = 60000)
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

	/**
	 * Verify that the multi-type writer keeps General MIDI percussion on
	 * channel 9. Tokenizers decode drum notes as NOTE events on channel 9;
	 * if the writer remaps that channel onto a melodic one, the drums are
	 * rendered as pitched notes of whatever program that channel carries.
	 */
	@Test(timeout = 60000)
	public void multiTypeWriterPreservesDrumChannel() throws Exception {
		int kick = 36;
		int melodicPitch = 60;
		int melodicPatch = 24;

		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(MidiNoteEvent.note(0, 0, 9, kick, 100, 50));
		events.add(MidiNoteEvent.patchChange(0, 0, 0, melodicPatch));
		events.add(MidiNoteEvent.note(100, 0, 0, melodicPitch, 80, 50));

		MidiFileReader reader = new MidiFileReader();
		File tempFile = File.createTempFile("midi-multi-type-drums-", ".mid");
		tempFile.deleteOnExit();

		reader.write(events, tempFile, MidiNoteEvent.TIME_RESOLUTION);
		List<MidiNoteEvent> readBack = reader.read(tempFile);

		assertEquals("Event count", events.size() - 1, readBack.size());
		assertEquals("Drum pitch", kick, readBack.get(0).getPitch());
		assertEquals("Drum note should remain on the percussion channel",
				MidiNoteEvent.DRUM_INSTRUMENT, readBack.get(0).getInstrument());
		assertEquals("Melodic pitch", melodicPitch, readBack.get(1).getPitch());
		assertEquals("Melodic note should keep its program",
				melodicPatch, readBack.get(1).getInstrument());
	}

	/**
	 * Verify that {@link MidiFileReader#read(File)} normalizes PPQ ticks to
	 * {@link MidiNoteEvent#TIME_RESOLUTION} ticks per <em>second</em> using the
	 * file's tempo. At 120 BPM with 480 ticks per beat, tick 960 is exactly one
	 * second into the file and a 480-tick note lasts half a second, so the note
	 * must be read back with onset 100 and duration 50.
	 */
	@Test(timeout = 60000)
	public void readNormalizesTicksToSecondsUsingTempo() throws Exception {
		Sequence sequence = new Sequence(Sequence.PPQ, 480);
		Track track = sequence.createTrack();
		track.add(tempoEvent(500_000, 0));
		addNote(track, 60, 960, 480);

		List<MidiNoteEvent> readBack = new MidiFileReader().read(writeTemp(sequence, "midi-tempo-"));

		assertEquals("Event count", 1, readBack.size());
		assertEquals("Onset of a note one second in", 100L, readBack.get(0).getOnset());
		assertEquals("Duration of a half-second note", 50L, readBack.get(0).getDuration());
	}

	/**
	 * Verify that a tempo change partway through a file is honored. The first
	 * 960 ticks run at 120 BPM (one second); the tempo then drops to 60 BPM, so
	 * a note starting 480 ticks later begins at two seconds and a 480-tick note
	 * after the change lasts one full second.
	 */
	@Test(timeout = 60000)
	public void readHonorsTempoChanges() throws Exception {
		Sequence sequence = new Sequence(Sequence.PPQ, 480);
		Track conductor = sequence.createTrack();
		conductor.add(tempoEvent(500_000, 0));
		conductor.add(tempoEvent(1_000_000, 960));
		Track notes = sequence.createTrack();
		addNote(notes, 60, 0, 480);
		addNote(notes, 64, 1440, 480);

		List<MidiNoteEvent> readBack = new MidiFileReader().read(writeTemp(sequence, "midi-tempo-change-"));

		assertEquals("Event count", 2, readBack.size());
		assertEquals("Onset before the tempo change", 0L, readBack.get(0).getOnset());
		assertEquals("Duration before the tempo change", 50L, readBack.get(0).getDuration());
		assertEquals("Onset after the tempo change", 200L, readBack.get(1).getOnset());
		assertEquals("Duration after the tempo change", 100L, readBack.get(1).getDuration());
	}

	/**
	 * Verify that a file produced by {@link MidiFileReader#write(List, File)}
	 * plays its events at {@link MidiNoteEvent#TIME_RESOLUTION} ticks per second
	 * when interpreted by a standard MIDI consumer: an event list spanning two
	 * seconds must produce a sequence two seconds long.
	 */
	@Test(timeout = 60000)
	public void writtenFileDurationMatchesEventTimes() throws Exception {
		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 100, 80, 0));
		events.add(new MidiNoteEvent(64, 100, 100, 80, 0));

		File tempFile = File.createTempFile("midi-written-length-", ".mid");
		tempFile.deleteOnExit();
		new MidiFileReader().write(events, tempFile);

		Sequence sequence = MidiSystem.getSequence(tempFile);
		assertEquals("Length in microseconds of a two-second event list",
				2_000_000L, sequence.getMicrosecondLength());
	}

	/**
	 * Verify that a note is labeled with the instrument program in effect on its
	 * channel at NOTE_ON, not with a later PROGRAM_CHANGE that arrives while the
	 * note is still sounding. Program 24 is selected before the note begins and
	 * program 48 is selected midway through it; the sounding note must keep
	 * program 24.
	 */
	@Test(timeout = 60000)
	public void readResolvesInstrumentAtNoteOn() throws Exception {
		Sequence sequence = new Sequence(Sequence.PPQ, 480);
		Track track = sequence.createTrack();
		track.add(tempoEvent(500_000, 0));
		track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 24, 0), 0));
		track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 80), 0));
		track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 48, 0), 240));
		track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 480));

		List<MidiNoteEvent> readBack = new MidiFileReader().read(writeTemp(sequence, "midi-prog-midnote-"));

		assertEquals("Event count", 1, readBack.size());
		assertEquals("Instrument must be the program at NOTE_ON, not the mid-note change",
				24, readBack.get(0).getInstrument());
	}

	/**
	 * Verify that {@link MidiFileReader#read(File)} normalizes SMPTE ticks to
	 * {@link MidiNoteEvent#TIME_RESOLUTION} ticks per <em>second</em> using the
	 * fixed ticks-per-second of the division. SMPTE_25 with 40 ticks per frame is
	 * 1000 ticks per second, so tick 1000 is one second in and a 500-tick note
	 * lasts half a second, giving onset 100 and duration 50.
	 */
	@Test(timeout = 60000)
	public void readNormalizesSmpteTicksToSeconds() throws Exception {
		Sequence sequence = new Sequence(Sequence.SMPTE_25, 40);
		Track track = sequence.createTrack();
		addNote(track, 60, 1000, 500);

		List<MidiNoteEvent> readBack = new MidiFileReader().read(writeTemp(sequence, "midi-smpte-"));

		assertEquals("Event count", 1, readBack.size());
		assertEquals("Onset of a note one second into an SMPTE file", 100L, readBack.get(0).getOnset());
		assertEquals("Duration of a half-second note in an SMPTE file", 50L, readBack.get(0).getDuration());
	}

	/** Creates a Set Tempo meta event with the given microseconds per quarter note. */
	private static MidiEvent tempoEvent(int microsPerBeat, long tick) throws InvalidMidiDataException {
		byte[] data = {
				(byte) (microsPerBeat >> 16 & 0xFF),
				(byte) (microsPerBeat >> 8 & 0xFF),
				(byte) (microsPerBeat & 0xFF)
		};
		return new MidiEvent(new MetaMessage(0x51, data, 3), tick);
	}

	/** Adds a NOTE_ON/NOTE_OFF pair on channel 0 to the given track. */
	private static void addNote(Track track, int pitch, long tick, long duration)
			throws InvalidMidiDataException {
		track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitch, 80), tick));
		track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitch, 0), tick + duration));
	}

	/** Writes the sequence to a temporary type-1 MIDI file. */
	private static File writeTemp(Sequence sequence, String prefix) throws Exception {
		File tempFile = File.createTempFile(prefix, ".mid");
		tempFile.deleteOnExit();
		MidiSystem.write(sequence, 1, tempFile);
		return tempFile;
	}
}
