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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages allocation and deallocation of synthesizer voices for polyphonic playback.
 * <p>
 * VoiceAllocator handles:
 * <ul>
 *   <li>Allocating free voices for new notes</li>
 *   <li>Tracking which voices are playing which notes</li>
 *   <li>Voice stealing when all voices are in use</li>
 *   <li>Finding voices by note number for noteOff events</li>
 * </ul>
 * <p>
 * Typical usage:
 * <pre>{@code
 * VoiceAllocator allocator = new VoiceAllocator(8);
 *
 * // Handle noteOn
 * VoiceState voice = allocator.allocate(60, 0.8);
 * if (voice != null) {
 *     // Start synthesis on voice.getVoiceIndex()
 * }
 *
 * // Handle noteOff
 * VoiceState released = allocator.release(60);
 * if (released != null) {
 *     // Begin release on released.getVoiceIndex()
 * }
 * }</pre>
 *
 * @see VoiceState
 */
public class VoiceAllocator {

	/**
	 * Voice stealing strategies when all voices are in use.
	 */
	public enum StealingStrategy {
		/**
		 * Steal the oldest playing voice.
		 */
		OLDEST,

		/**
		 * Steal a voice playing the same note (for retriggering).
		 */
		SAME_NOTE,

		/**
		 * Steal a voice in release phase first, then oldest.
		 */
		RELEASE_FIRST,

		/**
		 * Don't steal - reject the note.
		 */
		NONE
	}

	private final VoiceState[] voices;
	private final int maxVoices;
	private StealingStrategy stealingStrategy;

	/**
	 * Creates a voice allocator with the specified number of voices.
	 *
	 * @param maxVoices the maximum number of simultaneous voices
	 */
	public VoiceAllocator(int maxVoices) {
		this.maxVoices = maxVoices;
		this.voices = new VoiceState[maxVoices];
		this.stealingStrategy = StealingStrategy.RELEASE_FIRST;

		for (int i = 0; i < maxVoices; i++) {
			voices[i] = new VoiceState(i);
		}
	}

	/**
	 * Returns the maximum number of voices.
	 */
	public int getMaxVoices() {
		return maxVoices;
	}

	/**
	 * Returns the current voice stealing strategy.
	 */
	public StealingStrategy getStealingStrategy() {
		return stealingStrategy;
	}

	/**
	 * Sets the voice stealing strategy.
	 */
	public void setStealingStrategy(StealingStrategy strategy) {
		this.stealingStrategy = strategy;
	}

	/**
	 * Returns the voice state at the specified index.
	 */
	public VoiceState getVoice(int index) {
		return voices[index];
	}

	/**
	 * Returns all voice states.
	 */
	public VoiceState[] getVoices() {
		return voices;
	}

	/**
	 * Returns the number of currently active voices.
	 */
	public int getActiveVoiceCount() {
		int count = 0;
		for (VoiceState voice : voices) {
			if (voice.isActive()) count++;
		}
		return count;
	}

	/**
	 * Returns a list of all active voice states.
	 */
	public List<VoiceState> getActiveVoices() {
		List<VoiceState> active = new ArrayList<>();
		for (VoiceState voice : voices) {
			if (voice.isActive()) {
				active.add(voice);
			}
		}
		return active;
	}

	/**
	 * Allocates a voice for the specified note and velocity.
	 * <p>
	 * If no free voices are available, uses the stealing strategy to
	 * determine which voice to reuse.
	 *
	 * @param midiNote the MIDI note number (0-127)
	 * @param velocity the note velocity (0.0-1.0)
	 * @return the allocated voice state, or null if allocation failed
	 */
	public VoiceState allocate(int midiNote, double velocity) {
		// First, try to find a free voice
		VoiceState free = findFreeVoice();
		if (free != null) {
			free.noteOn(midiNote, velocity);
			return free;
		}

		// No free voice - try stealing
		VoiceState stolen = findVoiceToSteal(midiNote);
		if (stolen != null) {
			stolen.noteOn(midiNote, velocity);
			return stolen;
		}

		// Could not allocate
		return null;
	}

	/**
	 * Releases the voice playing the specified note.
	 *
	 * @param midiNote the MIDI note number to release
	 * @return the released voice state, or null if no voice was playing this note
	 */
	public VoiceState release(int midiNote) {
		VoiceState voice = findVoiceByNote(midiNote);
		if (voice != null && !voice.isReleasing()) {
			voice.noteOff();
			return voice;
		}
		return null;
	}

	/**
	 * Completely deactivates a voice (call after release envelope completes).
	 *
	 * @param voiceIndex the voice index to deactivate
	 */
	public void deactivate(int voiceIndex) {
		if (voiceIndex >= 0 && voiceIndex < maxVoices) {
			voices[voiceIndex].deactivate();
		}
	}

	/**
	 * Deactivates all voices (panic/all notes off).
	 */
	public void allNotesOff() {
		for (VoiceState voice : voices) {
			voice.deactivate();
		}
	}

	/**
	 * Finds a free (inactive) voice.
	 */
	private VoiceState findFreeVoice() {
		for (VoiceState voice : voices) {
			if (!voice.isActive()) {
				return voice;
			}
		}
		return null;
	}

	/**
	 * Finds a voice currently playing the specified note.
	 */
	private VoiceState findVoiceByNote(int midiNote) {
		for (VoiceState voice : voices) {
			if (voice.isActive() && voice.getMidiNote() == midiNote) {
				return voice;
			}
		}
		return null;
	}

	/**
	 * Finds a voice to steal based on the current stealing strategy.
	 */
	private VoiceState findVoiceToSteal(int midiNote) {
		switch (stealingStrategy) {
			case NONE:
				return null;

			case SAME_NOTE:
				VoiceState sameNote = findVoiceByNote(midiNote);
				if (sameNote != null) return sameNote;
				// Fall through to oldest if no same note
				return findOldestVoice();

			case RELEASE_FIRST:
				VoiceState releasing = findReleasingVoice();
				if (releasing != null) return releasing;
				// Fall through to oldest
				return findOldestVoice();

			case OLDEST:
			default:
				return findOldestVoice();
		}
	}

	/**
	 * Finds the oldest active voice (longest playing).
	 */
	private VoiceState findOldestVoice() {
		VoiceState oldest = null;
		long oldestAge = -1;

		for (VoiceState voice : voices) {
			if (voice.isActive()) {
				long age = voice.getAge();
				if (age > oldestAge) {
					oldestAge = age;
					oldest = voice;
				}
			}
		}

		return oldest;
	}

	/**
	 * Finds a voice that is in the release phase.
	 */
	private VoiceState findReleasingVoice() {
		for (VoiceState voice : voices) {
			if (voice.isActive() && voice.isReleasing()) {
				return voice;
			}
		}
		return null;
	}
}
