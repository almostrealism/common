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

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import java.util.Optional;

/**
 * A MIDI tempo, held as the number of microseconds per quarter note that the
 * Set Tempo meta event (type {@link #META_TYPE}) carries.
 *
 * <p>The Set Tempo event stores its tempo in a 24-bit field, so the slowest
 * tempo it can express is {@link #MAX_MICROSECONDS_PER_BEAT} microseconds per
 * beat (about 3.58 BPM). {@link #ofBpm(int)} clamps slower tempos to that
 * limit rather than letting the value wrap, and treats a tempo that is not
 * positive as the MIDI default of 120 BPM ({@link #DEFAULT}), which is the
 * tempo a MIDI file has before its first Set Tempo event.</p>
 *
 * @param microsPerBeat microseconds per quarter note, in
 *                      {@code [0, MAX_MICROSECONDS_PER_BEAT]}
 */
public record MidiTempo(int microsPerBeat) {
	/** MIDI Set Tempo meta-message type. */
	public static final int META_TYPE = 0x51;

	/** Largest value the 24-bit Set Tempo field can hold. */
	public static final int MAX_MICROSECONDS_PER_BEAT = 0xFFFFFF;

	/** Microseconds per minute, which converts between BPM and microseconds per beat. */
	private static final long MICROSECONDS_PER_MINUTE = 60_000_000L;

	/** The tempo MIDI assumes until the first Set Tempo event: 120 BPM. */
	public static final MidiTempo DEFAULT = new MidiTempo(500_000);

	/**
	 * Validates that the tempo fits the 24-bit Set Tempo field.
	 *
	 * @throws IllegalArgumentException if {@code microsPerBeat} is negative or
	 *                                  exceeds {@link #MAX_MICROSECONDS_PER_BEAT}
	 */
	public MidiTempo {
		if (microsPerBeat < 0 || microsPerBeat > MAX_MICROSECONDS_PER_BEAT) {
			throw new IllegalArgumentException("Tempo of " + microsPerBeat
					+ " microseconds per beat does not fit the Set Tempo field");
		}
	}

	/**
	 * Returns the tempo for {@code bpm} beats per minute, using the whole
	 * number of microseconds per beat. A {@code bpm} that is not positive
	 * yields {@link #DEFAULT}; one too slow for the Set Tempo field yields
	 * {@link #MAX_MICROSECONDS_PER_BEAT}, and one faster than a microsecond
	 * per beat yields one microsecond per beat rather than the degenerate
	 * zero tempo.
	 *
	 * @param bpm the tempo in beats per minute
	 * @return the tempo
	 */
	public static MidiTempo ofBpm(int bpm) {
		if (bpm <= 0) return DEFAULT;
		long micros = MICROSECONDS_PER_MINUTE / bpm;
		return new MidiTempo((int) Math.max(1, Math.min(MAX_MICROSECONDS_PER_BEAT, micros)));
	}

	/**
	 * Returns the tempo carried by {@code message} when it is a Set Tempo
	 * event with the three data bytes that event requires.
	 *
	 * @param message the meta message to read
	 * @return the tempo, or empty when {@code message} is not a complete
	 *         Set Tempo event
	 */
	public static Optional<MidiTempo> of(MetaMessage message) {
		byte[] data = message.getData();
		if (message.getType() != META_TYPE || data.length < 3) return Optional.empty();
		return Optional.of(new MidiTempo((data[0] & 0xFF) << 16 | (data[1] & 0xFF) << 8 | data[2] & 0xFF));
	}

	/**
	 * Returns this tempo in beats per minute, rounded to the nearest whole
	 * beat, or zero when {@link #microsPerBeat()} is zero.
	 *
	 * @return the tempo in beats per minute
	 */
	public int bpm() {
		return microsPerBeat == 0 ? 0 : (int) Math.round((double) MICROSECONDS_PER_MINUTE / microsPerBeat);
	}

	/**
	 * Encodes this tempo as a Set Tempo meta message.
	 *
	 * @return the meta message
	 * @throws InvalidMidiDataException if the message cannot be constructed
	 */
	public MetaMessage toMetaMessage() throws InvalidMidiDataException {
		byte[] data = {
				(byte) (microsPerBeat >> 16 & 0xFF),
				(byte) (microsPerBeat >> 8 & 0xFF),
				(byte) (microsPerBeat & 0xFF)
		};
		return new MetaMessage(META_TYPE, data, 3);
	}
}
