/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.pattern;

import org.almostrealism.collect.PackedCollection;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache for evaluated note audio across consecutive buffer ticks.
 *
 * <p>When a note spans multiple buffers, its audio producer is evaluated
 * once and the result is cached. Subsequent buffer ticks that overlap the
 * same note retrieve the cached audio instead of re-evaluating.</p>
 *
 * <p>The cache is keyed by absolute frame offset. Before each buffer tick,
 * {@link #evictBefore(int)} should be called to remove entries for notes
 * that have ended before the current buffer's start frame.</p>
 *
 * @see PatternFeatures
 * @see RenderedNoteAudio
 *
 * @author Michael Murray
 */
public class NoteAudioCache {
	private final Map<Integer, PackedCollection> cache = new HashMap<>();

	/**
	 * Returns cached audio for a note at the given offset, or null if not cached.
	 *
	 * @param noteOffset the note's absolute frame offset
	 * @return the cached audio, or null
	 */
	public PackedCollection get(int noteOffset) {
		return cache.get(noteOffset);
	}

	/**
	 * Stores evaluated audio for a note at the given offset.
	 *
	 * @param noteOffset the note's absolute frame offset
	 * @param audio the evaluated audio data
	 */
	public void put(int noteOffset, PackedCollection audio) {
		cache.put(noteOffset, audio);
	}

	/**
	 * Removes cached entries for notes that have ended before the given frame.
	 *
	 * <p>A note is considered ended when its start offset plus its audio
	 * length is at or before {@code currentStartFrame}.</p>
	 *
	 * @param currentStartFrame the start frame of the current buffer
	 */
	public void evictBefore(int currentStartFrame) {
		cache.entrySet().removeIf(entry -> {
			int noteStart = entry.getKey();
			int noteEnd = noteStart + entry.getValue().getShape().getCount();
			return noteEnd <= currentStartFrame;
		});
	}

	/**
	 * Returns the number of cached entries.
	 */
	public int size() {
		return cache.size();
	}

	/**
	 * Removes all cached entries.
	 */
	public void clear() {
		cache.clear();
	}
}
