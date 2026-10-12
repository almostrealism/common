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

package org.almostrealism.music.midi;

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
import java.util.TreeMap;

/**
 * Reads and writes standard MIDI files, converting between the
 * javax.sound.midi representation and {@link MidiNoteEvent} lists.
 *
 * <p>MIDI tick resolution is normalized to {@link MidiNoteEvent#TIME_RESOLUTION}
 * ticks per second. The reader tracks NOTE_ON/NOTE_OFF pairs and PROGRAM_CHANGE
 * messages to associate each note with its instrument.</p>
 *
 * <p>All six standard MIDI event types are supported for writing:
 * {@link MidiNoteEvent.EventType#NOTE}, {@link MidiNoteEvent.EventType#PATCH_CHANGE},
 * {@link MidiNoteEvent.EventType#CONTROL_CHANGE}, {@link MidiNoteEvent.EventType#SET_TEMPO},
 * {@link MidiNoteEvent.EventType#TIME_SIGNATURE}, and
 * {@link MidiNoteEvent.EventType#KEY_SIGNATURE}.</p>
 *
 * <p>Uses only the JDK {@code javax.sound.midi} API — no external dependencies.</p>
 *
 * @see MidiNoteEvent
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

	/** MIDI Set Tempo meta-message type. */
	private static final int SET_TEMPO = 0x51;

	/** Tempo assumed by MIDI until the first Set Tempo event (120 BPM). */
	private static final int DEFAULT_MICROSECONDS_PER_BEAT = 500_000;

	/** Microseconds per second, which is also the tempo of 60 BPM in microseconds per beat. */
	private static final int MICROSECONDS_PER_SECOND = 1_000_000;

	/**
	 * Read a standard MIDI file and extract all note events.
	 *
	 * <p>Tick times are normalized to {@link MidiNoteEvent#TIME_RESOLUTION}
	 * ticks per second regardless of the file's native resolution. For PPQ
	 * files the conversion follows the file's tempo map (Set Tempo meta events
	 * on any track, 120 BPM until the first one), as a MIDI player would.</p>
	 *
	 * @param midiFile the MIDI file to read
	 * @return sorted list of note events
	 * @throws IOException              if the file cannot be read
	 * @throws InvalidMidiDataException if the file contains invalid MIDI data
	 */
	public List<MidiNoteEvent> read(File midiFile) throws IOException, InvalidMidiDataException {
		Sequence sequence = MidiSystem.getSequence(midiFile);
		TempoMap tempoMap = new TempoMap(sequence);

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
						long onsetTick = tempoMap.toTimeTicks(onEvent.getTick());
						long offTick = tempoMap.toTimeTicks(midiEvent.getTick());
						long duration = Math.max(1, offTick - onsetTick);

						// TODO(review): program is resolved at NOTE_OFF; a PROGRAM_CHANGE mid-note re-labels the note
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
	 * <p>Events are written at {@link MidiNoteEvent#TIME_RESOLUTION} ticks
	 * per beat with a 60 BPM tempo, so one beat lasts one second and event
	 * ticks map directly to {@link MidiNoteEvent#TIME_RESOLUTION} ticks per
	 * second when the file is played or read back. Each distinct non-drum instrument is assigned its own MIDI channel
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
		Sequence sequence = new Sequence(Sequence.PPQ, MidiNoteEvent.TIME_RESOLUTION);
		Track track = sequence.createTrack();
		track.add(new MidiEvent(tempoMessage(MICROSECONDS_PER_SECOND), 0));

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
	 * handling all six standard MIDI event types.
	 *
	 * <p>All six event types are supported:</p>
	 * <ul>
	 *   <li>{@link MidiNoteEvent.EventType#NOTE} — writes NOTE_ON at onset
	 *       and NOTE_OFF at onset + duration, assigning a unique channel per
	 *       (track, channel) combination. Percussion events on channel 9
	 *       are always written to channel 9.</li>
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
	 * @param ticksPerBeat MIDI PPQ resolution
	 * @throws IOException              if the file cannot be written
	 * @throws InvalidMidiDataException if a MIDI message cannot be constructed
	 */
	public void write(List<MidiNoteEvent> events, File output, int ticksPerBeat)
			throws IOException, InvalidMidiDataException {
		Sequence sequence = new Sequence(Sequence.PPQ, ticksPerBeat);
		Track track = sequence.createTrack();

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
							: DEFAULT_MICROSECONDS_PER_BEAT;
					track.add(new MidiEvent(tempoMessage(microsPerBeat), tick));
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
	 * if the (track, channel) pair has not been seen before. Events on the
	 * percussion channel stay on {@link #DRUM_CHANNEL}, since remapping them
	 * would turn drums into pitched notes.
	 */
	private static int resolveChannel(MidiNoteEvent event,
			Map<Long, Integer> channelMap, int[] nextMidiChannel) {
		if (event.getChannel() == DRUM_CHANNEL) return DRUM_CHANNEL;

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
	 * Create a Set Tempo meta message (type 0x51) for the given tempo.
	 */
	private static MetaMessage tempoMessage(int microsPerBeat) throws InvalidMidiDataException {
		byte[] data = {
				(byte) (microsPerBeat >> 16 & 0xFF),
				(byte) (microsPerBeat >> 8 & 0xFF),
				(byte) (microsPerBeat & 0xFF)
		};
		return new MetaMessage(SET_TEMPO, data, 3);
	}

	/**
	 * The tempo map of a {@link Sequence}, converting MIDI ticks to
	 * {@link MidiNoteEvent#TIME_RESOLUTION} ticks per second.
	 *
	 * <p>For PPQ sequences the duration of a tick depends on the tempo in
	 * effect, which Set Tempo meta events may change at any point on any
	 * track; until the first one the MIDI default of 120 BPM applies. SMPTE
	 * sequences have a fixed number of ticks per second.</p>
	 */
	private static final class TempoMap {
		/** Elapsed microseconds at each tempo change, keyed by MIDI tick. */
		private final TreeMap<Long, Double> microsAtChange = new TreeMap<>();

		/** Microseconds per beat in effect from each tempo change, keyed by MIDI tick. */
		private final TreeMap<Long, Integer> tempoAtChange = new TreeMap<>();

		/** Ticks per beat (PPQ) or ticks per frame (SMPTE). */
		private final int resolution;

		/** Ticks per second for SMPTE sequences; zero for PPQ sequences. */
		private final double smpteTicksPerSecond;

		/**
		 * Build the tempo map from the Set Tempo meta events of every track.
		 */
		TempoMap(Sequence sequence) {
			this.resolution = sequence.getResolution();
			this.smpteTicksPerSecond = sequence.getDivisionType() == Sequence.PPQ
					? 0 : sequence.getDivisionType() * resolution;

			tempoAtChange.put(0L, DEFAULT_MICROSECONDS_PER_BEAT);
			for (Track track : sequence.getTracks()) {
				for (int i = 0; i < track.size(); i++) {
					MidiEvent event = track.get(i);
					if (event.getMessage() instanceof MetaMessage meta
							&& meta.getType() == SET_TEMPO && meta.getData().length >= 3) {
						byte[] data = meta.getData();
						tempoAtChange.put(event.getTick(),
								(data[0] & 0xFF) << 16 | (data[1] & 0xFF) << 8 | data[2] & 0xFF);
					}
				}
			}

			long previousTick = 0;
			double micros = 0;
			int previousTempo = DEFAULT_MICROSECONDS_PER_BEAT;
			for (Map.Entry<Long, Integer> change : tempoAtChange.entrySet()) {
				micros += (change.getKey() - previousTick) * (double) previousTempo / resolution;
				microsAtChange.put(change.getKey(), micros);
				previousTick = change.getKey();
				previousTempo = change.getValue();
			}
		}

		/**
		 * Convert a MIDI tick to elapsed microseconds from the start of the sequence.
		 */
		double toMicroseconds(long tick) {
			if (smpteTicksPerSecond > 0) {
				return tick * MICROSECONDS_PER_SECOND / smpteTicksPerSecond;
			}

			Map.Entry<Long, Integer> tempo = tempoAtChange.floorEntry(tick);
			return microsAtChange.get(tempo.getKey())
					+ (tick - tempo.getKey()) * (double) tempo.getValue() / resolution;
		}

		/**
		 * Convert a MIDI tick to {@link MidiNoteEvent#TIME_RESOLUTION} ticks per second.
		 */
		long toTimeTicks(long tick) {
			return Math.round(toMicroseconds(tick) * MidiNoteEvent.TIME_RESOLUTION
					/ MICROSECONDS_PER_SECOND);
		}
	}

	/**
	 * Pack channel and note number into a single long key for tracking pending notes.
	 */
	private static long packNoteKey(int channel, int note) {
		return ((long) channel << 8) | note;
	}
}
