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

package org.almostrealism.music.pattern;

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
 * <p>The cache is keyed by a note's absolute frame offset together with a
 * stable per-note {@code identity}. Coincident notes (chords, layered voices)
 * that begin at the same frame therefore keep distinct entries rather than
 * overwriting one another. The identity must be equal across buffer ticks for
 * the same note (so a note spanning buffers hits its own cached audio) and
 * distinct between different notes sharing an offset. Before each buffer tick,
 * {@link #evictBefore(int)} should be called to remove entries for notes that
 * have ended before the current buffer's start frame.</p>
 *
 * @see PatternFeatures
 * @see RenderedNoteAudio
 *
 * @author Michael Murray
 */
public class NoteAudioCache {
	/**
	 * Composite cache key: a note's absolute frame offset plus a stable identity
	 * that distinguishes coincident notes sharing that offset. {@code identity}
	 * may be {@code null}, in which case the offset alone identifies the entry.
	 *
	 * @param offset   the note's absolute frame offset
	 * @param identity the stable per-note identity, or {@code null}
	 */
	private record Key(int offset, Object identity) {}

	/** Map from composite note key to cached audio data. */
	private final Map<Key, PackedCollection> cache = new HashMap<>();

	/**
	 * Returns cached audio for the note at the given offset and identity, or null
	 * if not cached.
	 *
	 * @param noteOffset the note's absolute frame offset
	 * @param identity   the stable per-note identity, or {@code null}
	 * @return the cached audio, or null
	 */
	public PackedCollection get(int noteOffset, Object identity) {
		return cache.get(new Key(noteOffset, identity));
	}

	/**
	 * Stores evaluated audio for the note at the given offset and identity.
	 *
	 * <p>Audio displaced for the same note (same offset and identity) is destroyed:
	 * each cached entry is a standalone copy owned by the cache (renderNotes copies
	 * the evaluated note audio into a fresh {@link PackedCollection} before caching),
	 * so the displaced entry would otherwise be orphaned and leak native memory.</p>
	 *
	 * @param noteOffset the note's absolute frame offset
	 * @param identity   the stable per-note identity, or {@code null}
	 * @param audio the evaluated audio data
	 */
	public void put(int noteOffset, Object identity, PackedCollection audio) {
		PackedCollection previous = cache.put(new Key(noteOffset, identity), audio);
		if (previous != null && previous != audio) {
			previous.destroy();
		}
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
			int noteStart = entry.getKey().offset();
			int noteEnd = noteStart + entry.getValue().getShape().getCount();
			if (noteEnd <= currentStartFrame) {
				// Free the GPU/native memory backing the evicted note audio; without
				// this the buffers leak and a long-running render eventually exhausts
				// device memory. The cache owns this audio outright — each entry is a
				// standalone copy (see put), so nothing else references it after eviction.
				entry.getValue().destroy();
				return true;
			}
			return false;
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
		cache.values().forEach(PackedCollection::destroy);
		cache.clear();
	}
}
