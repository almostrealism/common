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

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuned;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * Interface for audio sources that can produce sound at different musical pitches.
 * Extends {@link KeyboardTuned} to provide keyboard-based pitch control, allowing
 * audio to be retrieved at specific key positions with automatic pitch shifting.
 * Implementations manage the relationship between root pitch, target pitch, and
 * audio duration adjustments.
 *
 * @see NoteAudioProvider
 * @see KeyboardTuned
 * @see KeyPosition
 */
public interface NoteAudio extends KeyboardTuned {
	/**
	 * Returns a producer yielding the pitch-adjusted audio for the given key position and channel.
	 *
	 * @param target  the key position (pitch) to generate audio for
	 * @param channel audio channel index (-1 for all channels)
	 * @return producer that generates the note audio
	 */
	Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel);

	/**
	 * Returns the playback duration in seconds for the given key position.
	 *
	 * @param target the key position
	 * @return duration in seconds
	 */
	double getDuration(KeyPosition<?> target);

	/**
	 * Returns the underlying WaveData used as the source audio.
	 *
	 * @return the source WaveData
	 */
	WaveData getWaveData();

	/**
	 * Returns the audio sample rate in Hz.
	 *
	 * @return sample rate in Hz
	 */
	int getSampleRate();

	/**
	 * Releases this note's entries in any shared resampled-audio cache it has populated,
	 * so the cache stops holding references to this note once its owner is done with it.
	 * The note stays usable: audio requested afterwards is computed and cached again.
	 *
	 * <p>The default does nothing, which is the complete behavior for a note that keeps
	 * no cached audio of its own. {@link NoteAudioProvider} drops the entries it created;
	 * {@link NoteAudioGroup} releases those of each member.</p>
	 */
	default void releaseCachedAudio() { }

	/**
	 * Returns the notes that hold cached audio on behalf of this note: those whose
	 * {@link #releaseCachedAudio()} actually drops cache entries. An owner deciding
	 * which notes it may release compares these, not the notes it holds directly,
	 * because the same cache-holding note can be reached both on its own and as part
	 * of a composite.
	 *
	 * <p>The default is this note alone. {@link NoteAudioGroup} returns its members.</p>
	 *
	 * @return a new list of the notes whose cached audio this note's release covers
	 */
	default List<NoteAudio> getCachedAudioOwners() {
		return List.of(this);
	}
}
