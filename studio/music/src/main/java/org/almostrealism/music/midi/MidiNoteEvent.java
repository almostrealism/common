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

/**
 * Represents a single MIDI note event with pitch, timing, velocity,
 * and instrument information.
 *
 * <p>Onset and duration are stored in ticks at the resolution defined
 * by {@link #TIME_RESOLUTION} (100 ticks per second).</p>
 *
 * <p>This class mirrors the MIDI note representation used throughout
 * the pattern-to-MIDI export pipeline. {@link KeyPosition} positions
 * are converted to MIDI pitch by adding {@link #PITCH_OFFSET} (21),
 * since {@code WesternChromatic.A0} has position 0 and MIDI A0 = 21.</p>
 *
 * @see org.almostrealism.music.pattern.PatternElement#toMidiEvents
 */
public class MidiNoteEvent implements Comparable<MidiNoteEvent> {

	/** Ticks per second for onset/duration quantization. */
	public static final int TIME_RESOLUTION = 100;

	/**
	 * Offset added to {@link org.almostrealism.audio.tone.KeyPosition#position()}
	 * to obtain a standard MIDI pitch number. WesternChromatic starts at A0 = 0,
	 * while MIDI A0 = 21.
	 */
	public static final int PITCH_OFFSET = 21;

	/** Default MIDI velocity when no automation data is available. */
	public static final int DEFAULT_VELOCITY = 100;

	/** Instrument number assigned to percussive (drum) notes. */
	public static final int DRUM_INSTRUMENT = 128;

	/** MIDI pitch number (0-127). */
	private final int pitch;

	/** Absolute onset time in ticks. */
	private final long onset;

	/** Duration in ticks. */
	private final long duration;

	/** MIDI velocity (0-127). */
	private final int velocity;

	/** MIDI instrument/program number (0-127, 128 for drums). */
	private final int instrument;

	/**
	 * Create a new MIDI note event.
	 *
	 * @param pitch      MIDI pitch number (0-127)
	 * @param onset      absolute onset time in ticks
	 * @param duration   duration in ticks
	 * @param velocity   MIDI velocity (0-127)
	 * @param instrument MIDI program number (0-127, 128 for drums)
	 */
	public MidiNoteEvent(int pitch, long onset, long duration, int velocity, int instrument) {
		this.pitch = pitch;
		this.onset = onset;
		this.duration = duration;
		this.velocity = velocity;
		this.instrument = instrument;
	}

	/** Returns the MIDI pitch number (0-127). */
	public int getPitch() { return pitch; }

	/** Returns the absolute onset time in ticks. */
	public long getOnset() { return onset; }

	/** Returns the duration in ticks. */
	public long getDuration() { return duration; }

	/** Returns the MIDI velocity (0-127). */
	public int getVelocity() { return velocity; }

	/** Returns the MIDI instrument/program number. */
	public int getInstrument() { return instrument; }

	/** Returns the octave derived from pitch (pitch / 12). */
	public int getOctave() { return pitch / 12; }

	/** Returns the pitch class derived from pitch (pitch % 12). */
	public int getPitchClass() { return pitch % 12; }

	@Override
	public int compareTo(MidiNoteEvent other) {
		int cmp = Long.compare(this.onset, other.onset);
		if (cmp != 0) return cmp;
		return Integer.compare(this.pitch, other.pitch);
	}

	@Override
	public String toString() {
		return String.format("MidiNoteEvent{pitch=%d, onset=%d, duration=%d, velocity=%d, instrument=%d}",
				pitch, onset, duration, velocity, instrument);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof MidiNoteEvent)) return false;
		MidiNoteEvent other = (MidiNoteEvent) obj;
		return pitch == other.pitch &&
				onset == other.onset &&
				duration == other.duration &&
				velocity == other.velocity &&
				instrument == other.instrument;
	}

	@Override
	public int hashCode() {
		int result = pitch;
		result = 31 * result + Long.hashCode(onset);
		result = 31 * result + Long.hashCode(duration);
		result = 31 * result + velocity;
		result = 31 * result + instrument;
		return result;
	}
}
