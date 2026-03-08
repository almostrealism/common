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

import org.almostrealism.audio.synth.PolyphonicSynthesizer;

import java.util.HashSet;
import java.util.Set;

/**
 * Bridges MIDI input to a {@link PolyphonicSynthesizer}.
 * <p>
 * MidiSynthesizerBridge implements {@link MidiInputListener} and routes
 * MIDI messages to the synthesizer. It handles:
 * <ul>
 *   <li>Note on/off with velocity curves</li>
 *   <li>Channel filtering (respond to specific channel or omni)</li>
 *   <li>Sustain pedal (CC64)</li>
 *   <li>All notes off (CC123)</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * PolyphonicSynthesizer synth = new PolyphonicSynthesizer(8);
 * MidiSynthesizerBridge bridge = new MidiSynthesizerBridge(synth);
 * bridge.setVelocityCurve(VelocityCurve.SOFT);
 * bridge.setChannel(0);  // Only respond to channel 1, or -1 for omni
 *
 * MidiInputConnection connection = manager.openInput(deviceInfo);
 * connection.addListener(bridge);
 * }</pre>
 *
 * @see PolyphonicSynthesizer
 * @see MidiInputListener
 */
public class MidiSynthesizerBridge implements MidiInputListener {

	private final PolyphonicSynthesizer synthesizer;
	private int channel;  // -1 for omni (respond to all channels)
	private VelocityCurve velocityCurve;
	private double velocityFloor;
	private boolean sustainEnabled;
	private boolean sustainPedalDown;
	private final Set<Integer> sustainedNotes;  // Notes held by sustain pedal

	/**
	 * Creates a bridge to the specified synthesizer.
	 *
	 * @param synthesizer the target synthesizer
	 */
	public MidiSynthesizerBridge(PolyphonicSynthesizer synthesizer) {
		this.synthesizer = synthesizer;
		this.channel = -1;  // Omni mode by default
		this.velocityCurve = VelocityCurve.LINEAR;
		this.velocityFloor = 0.0;
		this.sustainEnabled = true;
		this.sustainPedalDown = false;
		this.sustainedNotes = new HashSet<>();
	}

	/**
	 * Sets the MIDI channel to respond to.
	 *
	 * @param channel MIDI channel (0-15), or -1 for omni mode
	 */
	public void setChannel(int channel) {
		this.channel = channel;
	}

	/**
	 * Returns the current channel setting.
	 *
	 * @return channel (0-15) or -1 for omni
	 */
	public int getChannel() {
		return channel;
	}

	/**
	 * Sets the velocity response curve.
	 *
	 * @param curve the velocity curve to use
	 */
	public void setVelocityCurve(VelocityCurve curve) {
		this.velocityCurve = curve != null ? curve : VelocityCurve.LINEAR;
	}

	/**
	 * Returns the current velocity curve.
	 */
	public VelocityCurve getVelocityCurve() {
		return velocityCurve;
	}

	/**
	 * Sets the minimum velocity floor.
	 * Even the softest touch will produce at least this amplitude.
	 *
	 * @param floor minimum amplitude (0.0-1.0)
	 */
	public void setVelocityFloor(double floor) {
		this.velocityFloor = Math.max(0.0, Math.min(1.0, floor));
	}

	/**
	 * Returns the velocity floor.
	 */
	public double getVelocityFloor() {
		return velocityFloor;
	}

	/**
	 * Enables or disables sustain pedal support.
	 *
	 * @param enabled true to enable sustain pedal
	 */
	public void setSustainEnabled(boolean enabled) {
		this.sustainEnabled = enabled;
		if (!enabled) {
			releaseSustainedNotes();
		}
	}

	/**
	 * Returns true if sustain pedal is enabled.
	 */
	public boolean isSustainEnabled() {
		return sustainEnabled;
	}

	/**
	 * Returns the underlying synthesizer.
	 */
	public PolyphonicSynthesizer getSynthesizer() {
		return synthesizer;
	}

	// ========== MidiInputListener Implementation ==========

	@Override
	public void noteOn(int midiChannel, int note, int velocity) {
		if (!shouldRespondToChannel(midiChannel)) {
			return;
		}

		// Apply velocity curve
		double amplitude = velocityCurve.apply(velocity, velocityFloor);

		// Remove from sustained set if re-triggered
		sustainedNotes.remove(note);

		synthesizer.noteOn(note, amplitude);
	}

	@Override
	public void noteOff(int midiChannel, int note, int velocity) {
		if (!shouldRespondToChannel(midiChannel)) {
			return;
		}

		if (sustainEnabled && sustainPedalDown) {
			// Don't release - add to sustained set
			sustainedNotes.add(note);
		} else {
			synthesizer.noteOff(note);
		}
	}

	@Override
	public void controlChange(int midiChannel, int controller, int value) {
		if (!shouldRespondToChannel(midiChannel)) {
			return;
		}

		switch (controller) {
			case MidiInputListener.CC.SUSTAIN:
				handleSustainPedal(value >= 64);
				break;

			case MidiInputListener.CC.ALL_NOTES_OFF:
			case MidiInputListener.CC.ALL_SOUND_OFF:
				synthesizer.allNotesOff();
				sustainedNotes.clear();
				sustainPedalDown = false;
				break;

			case MidiInputListener.CC.RESET_CONTROLLERS:
				sustainPedalDown = false;
				sustainedNotes.clear();
				break;
		}
	}

	@Override
	public void pitchBend(int midiChannel, int value) {
		if (!shouldRespondToChannel(midiChannel)) {
			return;
		}

		// TODO: Implement pitch bend
		// Could modulate synthesizer frequency or connect to vibrato
	}

	// ========== Internal Methods ==========

	/**
	 * Checks if this bridge should respond to the given MIDI channel.
	 */
	private boolean shouldRespondToChannel(int midiChannel) {
		return channel == -1 || channel == midiChannel;
	}

	/**
	 * Handles sustain pedal press/release.
	 */
	private void handleSustainPedal(boolean down) {
		if (!sustainEnabled) {
			return;
		}

		boolean wasDown = sustainPedalDown;
		sustainPedalDown = down;

		// Release sustained notes when pedal is released
		if (wasDown && !down) {
			releaseSustainedNotes();
		}
	}

	/**
	 * Releases all notes held by the sustain pedal.
	 */
	private void releaseSustainedNotes() {
		for (int note : sustainedNotes) {
			synthesizer.noteOff(note);
		}
		sustainedNotes.clear();
	}
}
