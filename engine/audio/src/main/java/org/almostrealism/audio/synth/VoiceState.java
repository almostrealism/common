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

package org.almostrealism.audio.synth;

/**
 * Represents the state of a single synthesizer voice.
 * <p>
 * Tracks voice activity, the currently playing note, and timing information
 * for voice stealing decisions.
 *
 * @see VoiceAllocator
 */
public class VoiceState {
	private final int voiceIndex;
	private boolean active;
	private int midiNote;
	private double velocity;
	private long startTime;
	private boolean releasing;

	public VoiceState(int voiceIndex) {
		this.voiceIndex = voiceIndex;
		this.active = false;
		this.midiNote = -1;
		this.velocity = 0;
		this.startTime = 0;
		this.releasing = false;
	}

	/**
	 * Returns the index of this voice in the voice pool.
	 */
	public int getVoiceIndex() {
		return voiceIndex;
	}

	/**
	 * Returns true if this voice is currently playing a note.
	 */
	public boolean isActive() {
		return active;
	}

	/**
	 * Returns true if this voice is in the release phase.
	 */
	public boolean isReleasing() {
		return releasing;
	}

	/**
	 * Returns the MIDI note number currently being played, or -1 if idle.
	 */
	public int getMidiNote() {
		return midiNote;
	}

	/**
	 * Returns the velocity of the currently playing note.
	 */
	public double getVelocity() {
		return velocity;
	}

	/**
	 * Returns the timestamp when the note started.
	 */
	public long getStartTime() {
		return startTime;
	}

	/**
	 * Returns how long this voice has been active (for voice stealing).
	 */
	public long getAge() {
		return active ? System.nanoTime() - startTime : 0;
	}

	/**
	 * Activates this voice for a new note.
	 */
	void noteOn(int midiNote, double velocity) {
		this.active = true;
		this.midiNote = midiNote;
		this.velocity = velocity;
		this.startTime = System.nanoTime();
		this.releasing = false;
	}

	/**
	 * Begins the release phase for this voice.
	 */
	void noteOff() {
		this.releasing = true;
	}

	/**
	 * Completely deactivates this voice.
	 */
	void deactivate() {
		this.active = false;
		this.midiNote = -1;
		this.velocity = 0;
		this.releasing = false;
	}
}
