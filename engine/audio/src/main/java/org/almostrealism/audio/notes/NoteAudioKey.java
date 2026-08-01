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

package org.almostrealism.audio.notes;

import org.almostrealism.audio.tone.KeyPosition;

import java.util.Objects;

/**
 * Composite key that uniquely identifies a note audio instance by combining
 * a musical key position with an audio channel number. Used by {@link NoteAudioProvider}
 * for caching and retrieving pitch-shifted audio data for specific channels.
 * Implements proper equals and hashCode for use in hash-based collections.
 *
 * @see NoteAudioProvider
 * @see KeyPosition
 */
public class NoteAudioKey {
	/** The musical key position (pitch) component of this key. */
	private final KeyPosition<?> position;

	/** The audio channel index component of this key. */
	private final int audioChannel;

	/**
	 * Creates a NoteAudioKey for the given position and audio channel.
	 *
	 * @param position     the musical key position
	 * @param audioChannel the audio channel index
	 */
	public NoteAudioKey(KeyPosition<?> position, int audioChannel) {
		this.position = position;
		this.audioChannel = audioChannel;
	}

	/**
	 * Returns the musical key position component of this key.
	 *
	 * @return the key position
	 */
	public KeyPosition<?> getPosition() {
		return position;
	}

	/**
	 * Returns the audio channel index component of this key.
	 *
	 * @return the channel index
	 */
	public int getAudioChannel() {
		return audioChannel;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof NoteAudioKey)) return false;
		return audioChannel == ((NoteAudioKey) o).audioChannel &&
				Objects.equals(position, ((NoteAudioKey) o).position);
	}

	@Override
	public int hashCode() { return position.hashCode(); }
}
