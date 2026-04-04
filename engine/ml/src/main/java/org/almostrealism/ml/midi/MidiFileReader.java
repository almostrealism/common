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

package org.almostrealism.ml.midi;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes standard MIDI files, converting between the
 * javax.sound.midi representation and {@link MidiNoteEvent} lists.
 *
 * <p>MIDI tick resolution is normalized to {@link MidiTokenizer#TIME_RESOLUTION}
 * ticks per second. The reader tracks NOTE_ON/NOTE_OFF pairs and PROGRAM_CHANGE
 * messages to associate each note with its instrument.</p>
 *
 * <p>Uses only the JDK {@code javax.sound.midi} API -- no external dependencies.</p>
 *
 * @see MidiNoteEvent
 * @see MidiTokenizer
 */
public class MidiFileReader {

	/** MIDI NOTE_ON status nibble. */
	private static final int NOTE_ON = 0x90;

	/** MIDI NOTE_OFF status nibble. */
	private static final int NOTE_OFF = 0x80;

	/** MIDI PROGRAM_CHANGE status nibble. */
	private static final int PROGRAM_CHANGE = 0xC0;

	/** MIDI channel 10 (0-indexed as 9) is reserved for drums. */
	private static final int DRUM_CHANNEL = 9;

	/** Instrument number assigned to drum channel notes. */
	private static final int DRUM_INSTRUMENT = 128;

	/**
	 * Read a standard MIDI file and extract all note events.
	 *
	 * <p>Tick times are normalized to {@link MidiTokenizer#TIME_RESOLUTION}
	 * ticks per second regardless of the file's native resolution.</p>
	 *
	 * @param midiFile the MIDI file to read
	 * @return sorted list of note events
	 * @throws IOException              if the file cannot be read
	 * @throws InvalidMidiDataException if the file contains invalid MIDI data
	 */
	public List<MidiNoteEvent> read(File midiFile) throws IOException, InvalidMidiDataException {
		Sequence sequence = MidiSystem.getSequence(midiFile);
		double tickScale = computeTickScale(sequence);

		Map<Integer, Integer> channelPrograms = new HashMap<>();
		List<MidiNoteEvent> events = new ArrayList<>();

		for (Track track : sequence.getTracks()) {
			Map<Long, MidiEvent> pendingNotes = new HashMap<>();
			channelPrograms.clear();

			for (int i = 0; i < track.size(); i++) {
				MidiEvent midiEvent = track.get(i);
				MidiMessage message = midiEvent.getMessage();

				if (!(message instanceof ShortMessage)) continue;
				ShortMessage sm = (ShortMessage) message;

				int command = sm.getCommand();
				int channel = sm.getChannel();

				if (command == PROGRAM_CHANGE) {
					channelPrograms.put(channel, sm.getData1());
				} else if (command == NOTE_ON && sm.getData2() > 0) {
					long noteKey = packNoteKey(channel, sm.getData1());
					pendingNotes.put(noteKey, midiEvent);
				} else if (command == NOTE_OFF ||
						(command == NOTE_ON && sm.getData2() == 0)) {
					long noteKey = packNoteKey(channel, sm.getData1());
					MidiEvent onEvent = pendingNotes.remove(noteKey);
					if (onEvent != null) {
						ShortMessage onMsg = (ShortMessage) onEvent.getMessage();
						long onsetTick = Math.round(onEvent.getTick() * tickScale);
						long offTick = Math.round(midiEvent.getTick() * tickScale);
						long duration = Math.max(1, offTick - onsetTick);

						int instrument = channel == DRUM_CHANNEL
								? DRUM_INSTRUMENT
								: channelPrograms.getOrDefault(channel, 0);

						events.add(new MidiNoteEvent(
								onMsg.getData1(), onsetTick, duration,
								onMsg.getData2(), instrument));
					}
				}
			}
		}

		Collections.sort(events);
		return events;
	}

	/**
	 * Write a list of note events to a standard MIDI file.
	 *
	 * <p>Events are written at {@link MidiTokenizer#TIME_RESOLUTION} ticks
	 * per beat (assuming 120 BPM, so ticks map directly to centiseconds).
	 * Each distinct non-drum instrument is assigned its own MIDI channel
	 * (0-8, 10-15), with drum events always on channel 9. Up to 15
	 * non-drum instruments are supported; overflow instruments fall back
	 * to channel 0.</p>
	 *
	 * @param events the note events to write
	 * @param output the output MIDI file
	 * @throws IOException              if the file cannot be written
	 * @throws InvalidMidiDataException if MIDI messages cannot be constructed
	 */
	public void write(List<MidiNoteEvent> events, File output)
			throws IOException, InvalidMidiDataException {
		Sequence sequence = new Sequence(Sequence.PPQ, MidiTokenizer.TIME_RESOLUTION);
		Track track = sequence.createTrack();

		Map<Integer, Integer> instrumentToChannel = new HashMap<>();
		int nextChannel = 0;

		for (MidiNoteEvent event : events) {
			int instrument = event.getInstrument();
			int channel;

			if (instrument == DRUM_INSTRUMENT) {
				channel = DRUM_CHANNEL;
			} else {
				Integer mapped = instrumentToChannel.get(instrument);
				if (mapped != null) {
					channel = mapped;
				} else {
					while (nextChannel == DRUM_CHANNEL) nextChannel++;
					if (nextChannel <= 15) {
						channel = nextChannel;
						nextChannel++;
					} else {
						channel = 0;
					}
					instrumentToChannel.put(instrument, channel);
					ShortMessage pc = new ShortMessage(
							PROGRAM_CHANGE, channel, instrument, 0);
					track.add(new MidiEvent(pc, 0));
				}
			}

			ShortMessage noteOn = new ShortMessage(
					NOTE_ON, channel, event.getPitch(), event.getVelocity());
			track.add(new MidiEvent(noteOn, event.getOnset()));

			ShortMessage noteOff = new ShortMessage(
					NOTE_OFF, channel, event.getPitch(), 0);
			track.add(new MidiEvent(noteOff, event.getOnset() + event.getDuration()));
		}

		MidiSystem.write(sequence, 1, output);
	}

	/**
	 * Write a list of {@link MidiNoteEvent} objects to a standard MIDI file,
	 * handling all six event types.
	 *
	 * <p>All six event types are supported:</p>
	 * <ul>
	 *   <li>{@link MidiNoteEvent.EventType#NOTE} — writes NOTE_ON at onset
	 *       and NOTE_OFF at onset + duration, assigning a unique channel per
	 *       (track, channel) combination.</li>
	 *   <li>{@link MidiNoteEvent.EventType#PATCH_CHANGE} — writes a PROGRAM_CHANGE.</li>
	 *   <li>{@link MidiNoteEvent.EventType#CONTROL_CHANGE} — writes a CC message.</li>
	 *   <li>{@link MidiNoteEvent.EventType#SET_TEMPO} — writes a tempo meta-message
	 *       (type 0x51), converting BPM to microseconds per beat.</li>
	 *   <li>{@link MidiNoteEvent.EventType#TIME_SIGNATURE} — writes a time-signature
	 *       meta-message (type 0x58).</li>
	 *   <li>{@link MidiNoteEvent.EventType#KEY_SIGNATURE} — writes a key-signature
	 *       meta-message (type 0x59).</li>
	 * </ul>
	 *
	 * @param events       MIDI events to write
	 * @param output       the output MIDI file
	 * @param ticksPerBeat MIDI PPQ resolution (must match the value used during detokenization)
	 * @throws IOException              if the file cannot be written
	 * @throws InvalidMidiDataException if a MIDI message cannot be constructed
	 */
	public void write(List<MidiNoteEvent> events, File output, int ticksPerBeat)
			throws IOException, InvalidMidiDataException {
		Sequence sequence = new Sequence(Sequence.PPQ, ticksPerBeat);
		Track track = sequence.createTrack();

		// Map (skyTntTrack, skyTntChannel) → MIDI output channel (0-15, 9 reserved for drums)
		Map<Long, Integer> channelMap = new HashMap<>();
		int[] nextMidiChannel = {0};

		for (MidiNoteEvent event : events) {
			long tick = event.getTick();

			switch (event.getEventType()) {
				case NOTE: {
					int midiChannel = resolveChannel(event, channelMap, nextMidiChannel);
					int velocity = Math.max(1, event.getVelocity());
					track.add(new MidiEvent(
							new ShortMessage(NOTE_ON, midiChannel, event.getPitch(), velocity), tick));
					track.add(new MidiEvent(
							new ShortMessage(NOTE_OFF, midiChannel, event.getPitch(), 0),
							tick + Math.max(1, event.getDurationTicks())));
					break;
				}
				case PATCH_CHANGE: {
					int midiChannel = resolveChannel(event, channelMap, nextMidiChannel);
					track.add(new MidiEvent(
							new ShortMessage(PROGRAM_CHANGE, midiChannel, event.getPatch(), 0), tick));
					break;
				}
				case CONTROL_CHANGE: {
					int midiChannel = resolveChannel(event, channelMap, nextMidiChannel);
					track.add(new MidiEvent(
							new ShortMessage(0xB0, midiChannel, event.getController(), event.getCcValue()),
							tick));
					break;
				}
				case SET_TEMPO: {
					int microsPerBeat = event.getBpm() > 0
							? 60_000_000 / event.getBpm()
							: 500_000;  // default 120 BPM
					byte[] data = {
							(byte) (microsPerBeat >> 16 & 0xFF),
							(byte) (microsPerBeat >> 8 & 0xFF),
							(byte) (microsPerBeat & 0xFF)
					};
					track.add(new MidiEvent(new MetaMessage(0x51, data, 3), tick));
					break;
				}
				case TIME_SIGNATURE: {
					// dd encodes denominator - 1: 0→denom 2, 1→4, 2→8, 3→16 (MIDI log2)
					byte[] data = {
							(byte) (event.getNn() + 1),
							(byte) (event.getDd() + 1),
							(byte) 24,
							(byte) 8
					};
					track.add(new MidiEvent(new MetaMessage(0x58, data, 4), tick));
					break;
				}
				case KEY_SIGNATURE: {
					// sf is offset by 7: 0=7 flats, 7=C, 14=7 sharps
					byte[] data = {(byte) (event.getSf() - 7), (byte) event.getMi()};
					track.add(new MidiEvent(new MetaMessage(0x59, data, 2), tick));
					break;
				}
				default:
					break;
			}
		}

		MidiSystem.write(sequence, 1, output);
	}

	/**
	 * Resolve the output MIDI channel for an event, assigning a new channel
	 * if the (track, channel) pair has not been seen before.
	 */
	private static int resolveChannel(MidiNoteEvent event,
			Map<Long, Integer> channelMap, int[] nextMidiChannel) {
		long channelKey = ((long) event.getTrack() << 16) | event.getChannel();
		Integer midiChannel = channelMap.get(channelKey);
		if (midiChannel == null) {
			while (nextMidiChannel[0] == DRUM_CHANNEL) nextMidiChannel[0]++;
			midiChannel = nextMidiChannel[0] <= 15 ? nextMidiChannel[0]++ : 0;
			channelMap.put(channelKey, midiChannel);
		}
		return midiChannel;
	}

	/**
	 * Compute the scale factor to normalize ticks to {@link MidiTokenizer#TIME_RESOLUTION}.
	 */
	private double computeTickScale(Sequence sequence) {
		if (sequence.getDivisionType() == Sequence.PPQ) {
			int resolution = sequence.getResolution();
			return (double) MidiTokenizer.TIME_RESOLUTION / resolution;
		}
		double ticksPerSecond = sequence.getDivisionType() * sequence.getResolution();
		return MidiTokenizer.TIME_RESOLUTION / ticksPerSecond;
	}

	/**
	 * Pack channel and note number into a single long key for tracking pending notes.
	 */
	private static long packNoteKey(int channel, int note) {
		return ((long) channel << 8) | note;
	}
}
