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

package org.almostrealism.studio.persistence;

import org.almostrealism.audio.api.Audio;
import org.almostrealism.music.midi.MidiNoteEvent;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec utilities for {@link Audio.MidiPattern} — bridging the structured
 * proto representation to and from {@link MidiNoteEvent}, Standard MIDI File
 * (SMF) byte streams, and the AU host {@code note_on} / {@code note_off}
 * JSON command stream.
 *
 * <p>The structured form mirrors {@link MidiNoteEvent.EventType}: NOTE,
 * PATCH_CHANGE (proto {@code program_change}), CONTROL_CHANGE, SET_TEMPO,
 * TIME_SIGNATURE, KEY_SIGNATURE — plus a PITCH_BEND arm that the proto
 * carries today and that {@link MidiNoteEvent} does not yet enumerate.</p>
 *
 * <h2>Round-trip semantics</h2>
 *
 * <p>{@code MidiPattern → SMF bytes → MidiPattern} is lossless on the basic
 * message set (note, control change, pitch bend, program change, set tempo,
 * time signature, key signature). Because SMF allows multiple byte
 * representations of equivalent content (delta-time choices, track
 * partitioning, end-of-track markers), structural equality on the parsed
 * structured form is the sound equality check after a re-parse — not raw
 * byte equality.</p>
 *
 * <p>{@code MidiNoteEvent ↔ MidiPattern} is lossless because the structured
 * proto event vocabulary is a superset of {@link MidiNoteEvent.EventType}.</p>
 *
 * @see Audio.MidiPattern
 * @see MidiNoteEvent
 */
public final class MidiPatternPersistence {

	/** Default ticks-per-quarter when {@link Audio.MidiPattern#hasTicksPerQuarter()} is false. */
	public static final int DEFAULT_TICKS_PER_QUARTER = 480;

	/** AU host JSON {@code "cmd"} value for MIDI commands. */
	public static final String AU_HOST_CMD = "midi";

	/** AU host JSON {@code "type"} value for note-on. */
	public static final String AU_HOST_NOTE_ON = "note_on";

	/** AU host JSON {@code "type"} value for note-off. */
	public static final String AU_HOST_NOTE_OFF = "note_off";

	/** Utility class — not instantiable. */
	private MidiPatternPersistence() {}

	/**
	 * Returns the effective PPQ for a pattern, defaulting to
	 * {@link #DEFAULT_TICKS_PER_QUARTER} when not set.
	 *
	 * @param pattern the pattern
	 * @return the effective PPQ value
	 */
	public static int ticksPerQuarter(Audio.MidiPattern pattern) {
		return pattern.hasTicksPerQuarter() ? pattern.getTicksPerQuarter() : DEFAULT_TICKS_PER_QUARTER;
	}

	/**
	 * Builds a {@link Audio.MidiPattern} from a list of structured
	 * {@link MidiNoteEvent}s. Events are emitted in the input order; callers
	 * SHOULD pass them in tick order to satisfy the
	 * {@link Audio.MidiPattern#getEventsList()} ordering contract.
	 *
	 * @param events          source events
	 * @param ticksPerQuarter PPQ for the resulting pattern
	 * @return the structured pattern
	 */
	public static Audio.MidiPattern fromMidiNoteEvents(List<MidiNoteEvent> events, int ticksPerQuarter) {
		Audio.MidiPattern.Builder pattern = Audio.MidiPattern.newBuilder()
				.setTicksPerQuarter(ticksPerQuarter);
		for (MidiNoteEvent e : events) {
			pattern.addEvents(toProtoEvent(e));
		}
		return pattern.build();
	}

	/**
	 * Converts a {@link MidiNoteEvent} to its proto {@link Audio.MidiEvent}
	 * counterpart.
	 *
	 * @param event the canonical in-memory event
	 * @return the proto event
	 */
	public static Audio.MidiEvent toProtoEvent(MidiNoteEvent event) {
		Audio.MidiEvent.Builder b = Audio.MidiEvent.newBuilder()
				.setTick(event.getTick())
				.setTrack(event.getTrack())
				.setChannel(event.getChannel());
		switch (event.getEventType()) {
			case NOTE -> b.setNote(Audio.NoteEvent.newBuilder()
					.setPitch(event.getPitch())
					.setVelocity(event.getVelocity())
					.setDurationTicks(event.getDurationTicks()));
			case PATCH_CHANGE -> b.setProgramChange(Audio.ProgramChangeEvent.newBuilder()
					.setProgram(event.getPatch()));
			case CONTROL_CHANGE -> b.setControlChange(Audio.ControlChangeEvent.newBuilder()
					.setController(event.getController())
					.setValue(event.getCcValue()));
			case SET_TEMPO -> b.setSetTempo(Audio.SetTempoEvent.newBuilder()
					.setBpm(event.getBpm()));
			case TIME_SIGNATURE -> b.setTimeSignature(Audio.TimeSignatureEvent.newBuilder()
					.setNumerator(event.getNn() + 1)
					.setDenominator(1 << (event.getDd() + 1)));
			case KEY_SIGNATURE -> b.setKeySignature(Audio.KeySignatureEvent.newBuilder()
					.setSharpsFlats(event.getSf() - 7)
					.setMode(event.getMi()));
		}
		return b.build();
	}

	/**
	 * Decodes a {@link Audio.MidiPattern} into a list of canonical
	 * {@link MidiNoteEvent}s. The pitch-bend proto arm is dropped because
	 * {@link MidiNoteEvent.EventType} does not enumerate pitch-bend; all
	 * other arms map field-for-field.
	 *
	 * @param pattern the proto pattern
	 * @return the canonical event list, in proto order
	 */
	public static List<MidiNoteEvent> toMidiNoteEvents(Audio.MidiPattern pattern) {
		List<MidiNoteEvent> result = new ArrayList<>();
		for (Audio.MidiEvent e : pattern.getEventsList()) {
			MidiNoteEvent converted = fromProtoEvent(e);
			if (converted != null) result.add(converted);
		}
		return result;
	}

	/**
	 * Converts a single proto {@link Audio.MidiEvent} to a canonical
	 * {@link MidiNoteEvent}, returning {@code null} for event types that
	 * {@link MidiNoteEvent.EventType} does not cover (currently
	 * pitch-bend).
	 *
	 * @param event the proto event
	 * @return the canonical event, or {@code null} if not representable
	 */
	public static MidiNoteEvent fromProtoEvent(Audio.MidiEvent event) {
		long tick = event.getTick();
		int track = event.getTrack();
		int channel = event.getChannel();
		switch (event.getPayloadCase()) {
			case NOTE: {
				Audio.NoteEvent n = event.getNote();
				return MidiNoteEvent.note(tick, track, channel, n.getPitch(), n.getVelocity(), n.getDurationTicks());
			}
			case CONTROL_CHANGE: {
				Audio.ControlChangeEvent cc = event.getControlChange();
				return MidiNoteEvent.controlChange(tick, track, channel, cc.getController(), cc.getValue());
			}
			case PROGRAM_CHANGE: {
				Audio.ProgramChangeEvent pc = event.getProgramChange();
				return MidiNoteEvent.patchChange(tick, track, channel, pc.getProgram());
			}
			case SET_TEMPO: {
				Audio.SetTempoEvent st = event.getSetTempo();
				return MidiNoteEvent.setTempo(tick, track, st.getBpm());
			}
			case TIME_SIGNATURE: {
				Audio.TimeSignatureEvent ts = event.getTimeSignature();
				int nn = Math.max(0, ts.getNumerator() - 1);
				int dd = Integer.numberOfTrailingZeros(Math.max(1, ts.getDenominator())) - 1;
				return MidiNoteEvent.timeSignature(tick, track, nn, Math.max(0, dd));
			}
			case KEY_SIGNATURE: {
				Audio.KeySignatureEvent ks = event.getKeySignature();
				return MidiNoteEvent.keySignature(tick, track, ks.getSharpsFlats() + 7, ks.getMode());
			}
			case PITCH_BEND:
			case PAYLOAD_NOT_SET:
			default:
				return null;
		}
	}

	/**
	 * Parses a Standard MIDI File byte stream into a {@link Audio.MidiPattern}.
	 *
	 * <p>Only Type-0 and Type-1 SMFs are supported via {@link MidiSystem}.
	 * For Type-1 files, events from every track are merged into a single
	 * tick-ordered event list, with the originating track index preserved on
	 * each event.</p>
	 *
	 * @param data the SMF byte stream
	 * @return the structured pattern
	 * @throws IOException if the stream cannot be parsed
	 */
	public static Audio.MidiPattern fromSmfBytes(byte[] data) throws IOException {
		Sequence sequence;
		try {
			sequence = MidiSystem.getSequence(new ByteArrayInputStream(data));
		} catch (InvalidMidiDataException e) {
			throw new IOException("Invalid SMF data", e);
		}
		int ppq = sequence.getDivisionType() == Sequence.PPQ ? sequence.getResolution() : DEFAULT_TICKS_PER_QUARTER;
		Audio.MidiPattern.Builder pattern = Audio.MidiPattern.newBuilder().setTicksPerQuarter(ppq);

		Track[] tracks = sequence.getTracks();
		List<TrackedEvent> merged = new ArrayList<>();
		for (int trackIdx = 0; trackIdx < tracks.length; trackIdx++) {
			Track track = tracks[trackIdx];
			Map<Long, MidiEvent> pendingNotes = new HashMap<>();
			for (int i = 0; i < track.size(); i++) {
				MidiEvent midiEvent = track.get(i);
				MidiMessage message = midiEvent.getMessage();
				if (message instanceof ShortMessage) {
					convertShortMessage(merged, pendingNotes, (ShortMessage) message,
							midiEvent.getTick(), trackIdx);
				} else if (message instanceof MetaMessage) {
					convertMetaMessage(merged, (MetaMessage) message, midiEvent.getTick(), trackIdx);
				}
			}
		}
		merged.sort(TrackedEvent::compareByTick);
		for (TrackedEvent te : merged) pattern.addEvents(te.event);
		return pattern.build();
	}

	/**
	 * Converts a {@link ShortMessage} to a proto {@link Audio.MidiEvent},
	 * pairing note-on and note-off events into a single duration-bearing
	 * {@link Audio.NoteEvent}.
	 *
	 * @param out          accumulator for the converted events
	 * @param pendingNotes map of unmatched note-on events keyed by track/channel/pitch
	 * @param sm           the short message to convert
	 * @param tick         absolute tick of the message
	 * @param trackIdx     originating track index
	 */
	private static void convertShortMessage(List<TrackedEvent> out,
			Map<Long, MidiEvent> pendingNotes, ShortMessage sm, long tick, int trackIdx) {
		int command = sm.getCommand();
		int channel = sm.getChannel();
		int data1 = sm.getData1();
		int data2 = sm.getData2();
		switch (command) {
			case ShortMessage.NOTE_ON:
				if (data2 > 0) {
					long key = ((long) trackIdx << 24) | ((long) channel << 16) | data1;
					pendingNotes.put(key, new MidiEvent(sm, tick));
				} else {
					closeNote(out, pendingNotes, trackIdx, channel, data1, tick);
				}
				break;
			case ShortMessage.NOTE_OFF:
				closeNote(out, pendingNotes, trackIdx, channel, data1, tick);
				break;
			case ShortMessage.CONTROL_CHANGE:
				out.add(new TrackedEvent(tick, Audio.MidiEvent.newBuilder()
						.setTick(tick).setTrack(trackIdx).setChannel(channel)
						.setControlChange(Audio.ControlChangeEvent.newBuilder()
								.setController(data1).setValue(data2))
						.build()));
				break;
			case ShortMessage.PROGRAM_CHANGE:
				out.add(new TrackedEvent(tick, Audio.MidiEvent.newBuilder()
						.setTick(tick).setTrack(trackIdx).setChannel(channel)
						.setProgramChange(Audio.ProgramChangeEvent.newBuilder()
								.setProgram(data1))
						.build()));
				break;
			case ShortMessage.PITCH_BEND:
				int raw = (data2 << 7) | data1;
				out.add(new TrackedEvent(tick, Audio.MidiEvent.newBuilder()
						.setTick(tick).setTrack(trackIdx).setChannel(channel)
						.setPitchBend(Audio.PitchBendEvent.newBuilder().setValue(raw - 8192))
						.build()));
				break;
			default:
				break;
		}
	}

	/**
	 * Resolves a pending note-on against an incoming note-off, emitting a
	 * single duration-bearing proto {@link Audio.NoteEvent}.
	 *
	 * @param out      accumulator for the converted event
	 * @param pending  map of pending note-on events
	 * @param trackIdx originating track index
	 * @param channel  MIDI channel
	 * @param pitch    note pitch (0-127)
	 * @param offTick  absolute tick of the closing note-off
	 */
	private static void closeNote(List<TrackedEvent> out, Map<Long, MidiEvent> pending,
			int trackIdx, int channel, int pitch, long offTick) {
		long key = ((long) trackIdx << 24) | ((long) channel << 16) | pitch;
		MidiEvent on = pending.remove(key);
		if (on == null) return;
		ShortMessage sm = (ShortMessage) on.getMessage();
		long onTick = on.getTick();
		out.add(new TrackedEvent(onTick, Audio.MidiEvent.newBuilder()
				.setTick(onTick).setTrack(trackIdx).setChannel(channel)
				.setNote(Audio.NoteEvent.newBuilder()
						.setPitch(sm.getData1())
						.setVelocity(sm.getData2())
						.setDurationTicks(offTick - onTick))
				.build()));
	}

	/**
	 * Converts a {@link MetaMessage} (set tempo / time signature / key
	 * signature) to a proto {@link Audio.MidiEvent}.
	 *
	 * @param out      accumulator for the converted event
	 * @param mm       the meta message to convert
	 * @param tick     absolute tick of the message
	 * @param trackIdx originating track index
	 */
	private static void convertMetaMessage(List<TrackedEvent> out, MetaMessage mm, long tick, int trackIdx) {
		int type = mm.getType();
		byte[] data = mm.getData();
		switch (type) {
			case 0x51: { // set tempo
				if (data.length < 3) return;
				int micros = ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
				int bpm = micros == 0 ? 0 : (int) Math.round(60_000_000.0 / micros);
				out.add(new TrackedEvent(tick, Audio.MidiEvent.newBuilder()
						.setTick(tick).setTrack(trackIdx)
						.setSetTempo(Audio.SetTempoEvent.newBuilder().setBpm(bpm))
						.build()));
				break;
			}
			case 0x58: { // time signature
				if (data.length < 4) return;
				int numerator = data[0] & 0xff;
				int denominator = 1 << (data[1] & 0xff);
				out.add(new TrackedEvent(tick, Audio.MidiEvent.newBuilder()
						.setTick(tick).setTrack(trackIdx)
						.setTimeSignature(Audio.TimeSignatureEvent.newBuilder()
								.setNumerator(numerator).setDenominator(denominator))
						.build()));
				break;
			}
			case 0x59: { // key signature
				if (data.length < 2) return;
				int sf = (byte) data[0];
				int mi = data[1] & 0xff;
				out.add(new TrackedEvent(tick, Audio.MidiEvent.newBuilder()
						.setTick(tick).setTrack(trackIdx)
						.setKeySignature(Audio.KeySignatureEvent.newBuilder()
								.setSharpsFlats(sf).setMode(mi))
						.build()));
				break;
			}
			default:
				break;
		}
	}

	/**
	 * Serialises a {@link Audio.MidiPattern} into a Standard MIDI File byte
	 * stream (SMF Type-1 with one track, since the source patterns share a
	 * single tick clock).
	 *
	 * @param pattern the structured pattern
	 * @return the encoded SMF bytes
	 * @throws IOException if encoding fails
	 */
	public static byte[] toSmfBytes(Audio.MidiPattern pattern) throws IOException {
		int ppq = ticksPerQuarter(pattern);
		Sequence sequence;
		try {
			sequence = new Sequence(Sequence.PPQ, ppq);
		} catch (InvalidMidiDataException e) {
			throw new IOException("Invalid SMF parameters", e);
		}
		Track track = sequence.createTrack();
		try {
			for (Audio.MidiEvent event : pattern.getEventsList()) {
				appendToTrack(track, event);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			MidiSystem.write(sequence, 1, out);
			return out.toByteArray();
		} catch (InvalidMidiDataException e) {
			throw new IOException("Invalid MIDI event during SMF encoding", e);
		}
	}

	/**
	 * Appends a single proto {@link Audio.MidiEvent} to a {@link Track}.
	 *
	 * @param track destination track
	 * @param event proto event to encode
	 * @throws InvalidMidiDataException if the event cannot be encoded
	 */
	private static void appendToTrack(Track track, Audio.MidiEvent event) throws InvalidMidiDataException {
		long tick = event.getTick();
		int channel = clampChannel(event.getChannel());
		switch (event.getPayloadCase()) {
			case NOTE: {
				Audio.NoteEvent note = event.getNote();
				track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel,
						note.getPitch(), note.getVelocity()), tick));
				track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel,
						note.getPitch(), 0), tick + note.getDurationTicks()));
				break;
			}
			case CONTROL_CHANGE: {
				Audio.ControlChangeEvent cc = event.getControlChange();
				track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel,
						cc.getController(), cc.getValue()), tick));
				break;
			}
			case PROGRAM_CHANGE: {
				Audio.ProgramChangeEvent pc = event.getProgramChange();
				track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel,
						pc.getProgram(), 0), tick));
				break;
			}
			case PITCH_BEND: {
				int raw = event.getPitchBend().getValue() + 8192;
				if (raw < 0) raw = 0;
				if (raw > 0x3fff) raw = 0x3fff;
				track.add(new MidiEvent(new ShortMessage(ShortMessage.PITCH_BEND, channel,
						raw & 0x7f, (raw >> 7) & 0x7f), tick));
				break;
			}
			case SET_TEMPO: {
				int bpm = Math.max(1, event.getSetTempo().getBpm());
				int micros = (int) (60_000_000L / bpm);
				byte[] data = new byte[] {
						(byte) ((micros >> 16) & 0xff),
						(byte) ((micros >> 8) & 0xff),
						(byte) (micros & 0xff)
				};
				track.add(new MidiEvent(new MetaMessage(0x51, data, 3), tick));
				break;
			}
			case TIME_SIGNATURE: {
				Audio.TimeSignatureEvent ts = event.getTimeSignature();
				int denomLog2 = Integer.numberOfTrailingZeros(Math.max(1, ts.getDenominator()));
				byte[] data = new byte[] {
						(byte) ts.getNumerator(),
						(byte) denomLog2,
						(byte) 24,
						(byte) 8
				};
				track.add(new MidiEvent(new MetaMessage(0x58, data, 4), tick));
				break;
			}
			case KEY_SIGNATURE: {
				Audio.KeySignatureEvent ks = event.getKeySignature();
				byte[] data = new byte[] {
						(byte) ks.getSharpsFlats(),
						(byte) ks.getMode()
				};
				track.add(new MidiEvent(new MetaMessage(0x59, data, 2), tick));
				break;
			}
			case PAYLOAD_NOT_SET:
			default:
				break;
		}
	}

	/**
	 * Clamps a channel value into the MIDI-valid range [0, 15].
	 *
	 * @param channel the input value
	 * @return the clamped value
	 */
	private static int clampChannel(int channel) {
		if (channel < 0) return 0;
		if (channel > 15) return 15;
		return channel;
	}

	/**
	 * Serialises a {@link Audio.MidiPattern} into the AU host's
	 * {@code note_on} / {@code note_off} JSON command stream.
	 *
	 * <p>Each {@link Audio.NoteEvent} produces two commands: a {@code note_on}
	 * at {@code tick} and a deferred {@code note_off} at
	 * {@code tick + duration_ticks}. Non-note events that the AU host does
	 * not consume (set tempo, time signature, key signature) are skipped.
	 * CC, pitch-bend, and program-change arms are also skipped today —
	 * the AU host JSON protocol does not yet expose them.</p>
	 *
	 * <p>Output order matches the AU host's expectation: emissions are sorted
	 * by absolute tick, with note-offs that coincide with note-ons emitted
	 * after the note-ons (so a re-trigger at the same tick does not
	 * accidentally silence the new note before it fires).</p>
	 *
	 * @param pattern  the structured pattern
	 * @param pluginId the AU plugin instance id to embed in the {@code "id"} field
	 * @return JSON command lines, one per emitted command
	 */
	public static List<String> toAuHostJsonCommands(Audio.MidiPattern pattern, String pluginId) {
		List<long[]> emissions = new ArrayList<>();
		for (Audio.MidiEvent event : pattern.getEventsList()) {
			if (event.getPayloadCase() != Audio.MidiEvent.PayloadCase.NOTE) continue;
			Audio.NoteEvent note = event.getNote();
			emissions.add(new long[] { event.getTick(), 0, note.getPitch(), note.getVelocity() });
			emissions.add(new long[] { event.getTick() + note.getDurationTicks(), 1, note.getPitch(), 0 });
		}
		emissions.sort((a, b) -> {
			int cmp = Long.compare(a[0], b[0]);
			if (cmp != 0) return cmp;
			return Long.compare(a[1], b[1]);
		});
		List<String> commands = new ArrayList<>(emissions.size());
		for (long[] e : emissions) {
			String type = e[1] == 0 ? AU_HOST_NOTE_ON : AU_HOST_NOTE_OFF;
			commands.add(formatAuHostCommand(pluginId, type, (int) e[2], (int) e[3]));
		}
		return commands;
	}

	/**
	 * Formats a single AU host MIDI command JSON line.
	 *
	 * @param pluginId AU plugin instance id (escaped before embedding)
	 * @param type     command type (note_on / note_off)
	 * @param note     MIDI pitch
	 * @param velocity MIDI velocity
	 * @return one JSON command line
	 */
	private static String formatAuHostCommand(String pluginId, String type, int note, int velocity) {
		return "{\"cmd\":\"" + AU_HOST_CMD + "\",\"id\":\"" + escape(pluginId)
				+ "\",\"type\":\"" + type + "\",\"note\":" + note
				+ ",\"velocity\":" + velocity + "}";
	}

	/**
	 * Escapes special characters for embedding into a JSON string literal.
	 *
	 * @param s the input string
	 * @return the escaped representation
	 */
	private static String escape(String s) {
		if (s == null) return "";
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> b.append(c);
			}
		}
		return b.toString();
	}

	/**
	 * Pair of (tick, proto-event) used while merging multi-track SMFs into a
	 * single tick-ordered event stream.
	 */
	private static final class TrackedEvent {
		/** Absolute tick of the event. */
		private final long tick;
		/** Proto event payload. */
		private final Audio.MidiEvent event;

		/**
		 * Creates a tracked event.
		 *
		 * @param tick  absolute tick
		 * @param event the proto event
		 */
		TrackedEvent(long tick, Audio.MidiEvent event) {
			this.tick = tick;
			this.event = event;
		}

		/**
		 * Comparator helper for tick-ordering tracked events.
		 *
		 * @param a left side
		 * @param b right side
		 * @return negative / zero / positive per {@link Long#compare(long, long)}
		 */
		static int compareByTick(TrackedEvent a, TrackedEvent b) {
			return Long.compare(a.tick, b.tick);
		}
	}
}
