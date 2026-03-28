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

/**
 * A compound MIDI token consisting of 6 discrete attributes that together
 * represent a single musical note. This is the tokenized representation
 * used as input to the Moonbeam transformer model.
 *
 * <p>The 6 attributes and their vocabulary sizes:</p>
 * <table>
 * <caption>Compound Token Attributes</caption>
 *   <tr><th>Index</th><th>Attribute</th><th>Vocab Size</th><th>Description</th></tr>
 *   <tr><td>0</td><td>onset</td><td>4099</td><td>Relative onset delta in ticks</td></tr>
 *   <tr><td>1</td><td>duration</td><td>4099</td><td>Absolute duration in ticks</td></tr>
 *   <tr><td>2</td><td>octave</td><td>13</td><td>pitch / 12</td></tr>
 *   <tr><td>3</td><td>pitchClass</td><td>14</td><td>pitch % 12 (+ 2 reserved for SOS/EOS)</td></tr>
 *   <tr><td>4</td><td>instrument</td><td>131</td><td>MIDI program (128 for drums, + 2 reserved)</td></tr>
 *   <tr><td>5</td><td>velocity</td><td>130</td><td>MIDI velocity (+ 2 reserved)</td></tr>
 * </table>
 *
 * <p>Special tokens use sentinel values: SOS = -1, EOS = -2, PAD = -3,
 * FILL_START = -4, FILL_END = -5.</p>
 *
 * @see MidiTokenizer
 * @see MoonbeamConfig
 */
public class MidiCompoundToken {
	/** Number of attributes per compound token. */
	public static final int ATTRIBUTE_COUNT = 6;

	/** Sentinel value for Start-Of-Sequence in all attributes. */
	public static final int SOS_VALUE = -1;

	/** Sentinel value for End-Of-Sequence in all attributes. */
	public static final int EOS_VALUE = -2;

	/** Sentinel value for padding in all attributes. */
	public static final int PAD_VALUE = -3;

	/** Sentinel value for Fill-Start delimiter in all attributes. */
	public static final int FILL_START_VALUE = -4;

	/** Sentinel value for Fill-End delimiter in all attributes. */
	public static final int FILL_END_VALUE = -5;

	/** Relative onset delta in ticks (0 to 4098, or sentinel). */
	private final int onset;

	/** Duration in ticks (0 to 4098, or sentinel). */
	private final int duration;

	/** Octave number (0-10, or sentinel). */
	private final int octave;

	/** Pitch class (0-11, or sentinel). */
	private final int pitchClass;

	/** Instrument/program number (0-128, or sentinel). */
	private final int instrument;

	/** Velocity (0-127, or sentinel). */
	private final int velocity;

	/**
	 * Create a compound token with explicit attribute values.
	 *
	 * @param onset      relative onset delta in ticks
	 * @param duration   duration in ticks
	 * @param octave     octave number (0-10)
	 * @param pitchClass pitch class (0-11)
	 * @param instrument MIDI program number (0-128)
	 * @param velocity   MIDI velocity (0-127)
	 */
	public MidiCompoundToken(int onset, int duration, int octave,
							 int pitchClass, int instrument, int velocity) {
		this.onset = onset;
		this.duration = duration;
		this.octave = octave;
		this.pitchClass = pitchClass;
		this.instrument = instrument;
		this.velocity = velocity;
	}

	/** Returns the relative onset delta in ticks. */
	public int getOnset() { return onset; }

	/** Returns the duration in ticks. */
	public int getDuration() { return duration; }

	/** Returns the octave number. */
	public int getOctave() { return octave; }

	/** Returns the pitch class. */
	public int getPitchClass() { return pitchClass; }

	/** Returns the MIDI instrument/program number. */
	public int getInstrument() { return instrument; }

	/** Returns the MIDI velocity. */
	public int getVelocity() { return velocity; }

	/**
	 * Returns all 6 attribute values as an array.
	 * Order: onset, duration, octave, pitchClass, instrument, velocity.
	 */
	public int[] toArray() {
		return new int[]{onset, duration, octave, pitchClass, instrument, velocity};
	}

	/**
	 * Create a compound token from an array of 6 attribute values.
	 *
	 * @param values array of exactly 6 values in order: onset, duration, octave, pitchClass, instrument, velocity
	 * @return a new compound token
	 * @throws IllegalArgumentException if values does not have exactly 6 elements
	 */
	public static MidiCompoundToken fromArray(int[] values) {
		if (values.length != ATTRIBUTE_COUNT) {
			throw new IllegalArgumentException(
					"Expected " + ATTRIBUTE_COUNT + " values but got " + values.length);
		}
		return new MidiCompoundToken(values[0], values[1], values[2],
				values[3], values[4], values[5]);
	}

	/** Returns true if this is a Start-Of-Sequence token. */
	public boolean isSOS() { return onset == SOS_VALUE; }

	/** Returns true if this is an End-Of-Sequence token. */
	public boolean isEOS() { return onset == EOS_VALUE; }

	/** Returns true if this is a padding token. */
	public boolean isPAD() { return onset == PAD_VALUE; }

	/** Returns true if this is a Fill-Start delimiter token. */
	public boolean isFillStart() { return onset == FILL_START_VALUE; }

	/** Returns true if this is a Fill-End delimiter token. */
	public boolean isFillEnd() { return onset == FILL_END_VALUE; }

	/** Returns true if this is any fill delimiter token (FILL_START or FILL_END). */
	public boolean isFill() { return isFillStart() || isFillEnd(); }

	/** Returns true if this is a special token (SOS, EOS, PAD, FILL_START, or FILL_END). */
	public boolean isSpecial() { return onset < 0; }

	/** Creates the Start-Of-Sequence special token. */
	public static MidiCompoundToken sos() {
		return new MidiCompoundToken(SOS_VALUE, SOS_VALUE, SOS_VALUE,
				SOS_VALUE, SOS_VALUE, SOS_VALUE);
	}

	/** Creates the End-Of-Sequence special token. */
	public static MidiCompoundToken eos() {
		return new MidiCompoundToken(EOS_VALUE, EOS_VALUE, EOS_VALUE,
				EOS_VALUE, EOS_VALUE, EOS_VALUE);
	}

	/** Creates a padding special token. */
	public static MidiCompoundToken pad() {
		return new MidiCompoundToken(PAD_VALUE, PAD_VALUE, PAD_VALUE,
				PAD_VALUE, PAD_VALUE, PAD_VALUE);
	}

	/** Creates a Fill-Start delimiter token, marking the beginning of a fill region. */
	public static MidiCompoundToken fillStart() {
		return new MidiCompoundToken(FILL_START_VALUE, FILL_START_VALUE, FILL_START_VALUE,
				FILL_START_VALUE, FILL_START_VALUE, FILL_START_VALUE);
	}

	/** Creates a Fill-End delimiter token, marking the end of a fill region. */
	public static MidiCompoundToken fillEnd() {
		return new MidiCompoundToken(FILL_END_VALUE, FILL_END_VALUE, FILL_END_VALUE,
				FILL_END_VALUE, FILL_END_VALUE, FILL_END_VALUE);
	}

	@Override
	public String toString() {
		if (isSOS()) return "MidiCompoundToken{SOS}";
		if (isEOS()) return "MidiCompoundToken{EOS}";
		if (isPAD()) return "MidiCompoundToken{PAD}";
		if (isFillStart()) return "MidiCompoundToken{FILL_START}";
		if (isFillEnd()) return "MidiCompoundToken{FILL_END}";
		return String.format("MidiCompoundToken{onset=%d, dur=%d, oct=%d, pc=%d, inst=%d, vel=%d}",
				onset, duration, octave, pitchClass, instrument, velocity);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof MidiCompoundToken)) return false;
		MidiCompoundToken other = (MidiCompoundToken) obj;
		return onset == other.onset &&
				duration == other.duration &&
				octave == other.octave &&
				pitchClass == other.pitchClass &&
				instrument == other.instrument &&
				velocity == other.velocity;
	}

	@Override
	public int hashCode() {
		int result = onset;
		result = 31 * result + duration;
		result = 31 * result + octave;
		result = 31 * result + pitchClass;
		result = 31 * result + instrument;
		result = 31 * result + velocity;
		return result;
	}
}
