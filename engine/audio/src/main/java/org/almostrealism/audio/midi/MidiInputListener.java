/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.midi;

/**
 * Listener interface for receiving MIDI input messages.
 * <p>
 * Implementations receive callbacks for various MIDI message types including
 * notes, control changes, pitch bend, and timing messages. All methods have
 * default empty implementations, allowing listeners to override only the
 * messages they care about.
 * <p>
 * Note: Callbacks occur on the MIDI thread. Implementations should avoid
 * blocking operations and use thread-safe communication with the audio thread.
 *
 * @see MidiInputConnection
 * @see MidiSynthesizerBridge
 */
public interface MidiInputListener {

	/**
	 * Called when a note-on message is received.
	 *
	 * @param channel MIDI channel (0-15)
	 * @param note MIDI note number (0-127)
	 * @param velocity note velocity (0-127, 0 may indicate note-off)
	 */
	default void noteOn(int channel, int note, int velocity) {}

	/**
	 * Called when a note-off message is received.
	 *
	 * @param channel MIDI channel (0-15)
	 * @param note MIDI note number (0-127)
	 * @param velocity release velocity (0-127, often ignored)
	 */
	default void noteOff(int channel, int note, int velocity) {}

	/**
	 * Called when a control change (CC) message is received.
	 *
	 * @param channel MIDI channel (0-15)
	 * @param controller CC number (0-127)
	 * @param value CC value (0-127)
	 */
	default void controlChange(int channel, int controller, int value) {}

	/**
	 * Called when a pitch bend message is received.
	 *
	 * @param channel MIDI channel (0-15)
	 * @param value pitch bend value (0-16383, 8192 is center)
	 */
	default void pitchBend(int channel, int value) {}

	/**
	 * Called when a program change message is received.
	 *
	 * @param channel MIDI channel (0-15)
	 * @param program program number (0-127)
	 */
	default void programChange(int channel, int program) {}

	/**
	 * Called when a channel aftertouch (pressure) message is received.
	 *
	 * @param channel MIDI channel (0-15)
	 * @param pressure pressure value (0-127)
	 */
	default void aftertouch(int channel, int pressure) {}

	/**
	 * Called when a polyphonic aftertouch message is received.
	 *
	 * @param channel MIDI channel (0-15)
	 * @param note MIDI note number (0-127)
	 * @param pressure pressure value (0-127)
	 */
	default void polyAftertouch(int channel, int note, int pressure) {}

	/**
	 * Called when a MIDI clock tick is received (24 ticks per quarter note).
	 */
	default void clock() {}

	/**
	 * Called when a MIDI start message is received.
	 */
	default void start() {}

	/**
	 * Called when a MIDI stop message is received.
	 */
	default void stop() {}

	/**
	 * Called when a MIDI continue message is received.
	 */
	default void midiContinue() {}

	/**
	 * Common MIDI CC numbers.
	 */
	interface CC {
		/** Modulation wheel (CC 1). */
		int MODULATION = 1;
		/** Breath controller (CC 2). */
		int BREATH = 2;
		/** Foot pedal (CC 4). */
		int FOOT = 4;
		/** Portamento time (CC 5). */
		int PORTAMENTO_TIME = 5;
		/** Channel volume (CC 7). */
		int VOLUME = 7;
		/** Balance (CC 8). */
		int BALANCE = 8;
		/** Stereo pan position (CC 10). */
		int PAN = 10;
		/** Expression controller (CC 11). */
		int EXPRESSION = 11;
		/** Sustain pedal (CC 64). */
		int SUSTAIN = 64;
		/** Portamento on/off (CC 65). */
		int PORTAMENTO = 65;
		/** Sostenuto pedal (CC 66). */
		int SOSTENUTO = 66;
		/** Soft pedal (CC 67). */
		int SOFT_PEDAL = 67;
		/** Legato footswitch (CC 68). */
		int LEGATO = 68;
		/** Hold 2 footswitch (CC 69). */
		int HOLD_2 = 69;
		/** Filter resonance / timbre (CC 71). */
		int RESONANCE = 71;
		/** Envelope release time (CC 72). */
		int RELEASE_TIME = 72;
		/** Envelope attack time (CC 73). */
		int ATTACK_TIME = 73;
		/** Filter cutoff frequency (CC 74). */
		int CUTOFF = 74;
		/** Envelope decay time (CC 75). */
		int DECAY_TIME = 75;
		/** LFO vibrato rate (CC 76). */
		int VIBRATO_RATE = 76;
		/** LFO vibrato depth (CC 77). */
		int VIBRATO_DEPTH = 77;
		/** LFO vibrato delay (CC 78). */
		int VIBRATO_DELAY = 78;
		/** All sound off (CC 120). */
		int ALL_SOUND_OFF = 120;
		/** Reset all controllers to default (CC 121). */
		int RESET_CONTROLLERS = 121;
		/** All notes off (CC 123). */
		int ALL_NOTES_OFF = 123;
	}
}
