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

package org.almostrealism.audio.data;

import java.io.File;
import java.util.Collections;
import java.util.Map;

/**
 * Where each piece of content is, and how current that answer is.
 *
 * <p>Content is addressed by what it is rather than by where it is, so
 * resolving an identifier means searching for it. Searching costs a walk of
 * everything, which is affordable once and not once per identifier. This
 * remembers the answers.</p>
 *
 * <p>It also counts how many times it has been rebuilt. Anything worked out
 * from an index — which files a set of records names, what is known about each
 * of them — is only as current as the index it was worked out from, and the
 * deriver has no other way to find out that the index moved on. Being told
 * would mean whatever changed the library having to know what had been derived
 * from it, which it has no reason to.</p>
 *
 * <p>Replaced wholesale rather than mutated, so a reader either sees the index
 * as it was or as it is.</p>
 */
public class ContentIndex {
	/** Where each identifier's content is, or {@code null} when unindexed. */
	private volatile Map<String, File> entries;

	/** How many times the index has been replaced. */
	private volatile long generation;

	/**
	 * Returns the file holding the given content, from the index alone.
	 *
	 * <p>Never searches. A caller that is being asked a question while the
	 * thing it would search is still being built cannot search — see
	 * {@code AudioLibrary.indexedFileFor} for the case this exists for.</p>
	 *
	 * @param identifier the content identifier
	 * @return the file, or {@code null} if the index cannot say
	 */
	public File fileFor(String identifier) {
		if (identifier == null || identifier.isBlank()) return null;

		Map<String, File> current = entries;
		if (current == null) return null;

		File indexed = current.get(identifier);
		return indexed != null && indexed.isFile() ? indexed : null;
	}

	/**
	 * Adopts a new set of entries, and records that the index moved on.
	 *
	 * @param entries where each identifier's content is
	 */
	public synchronized void replace(Map<String, File> entries) {
		this.entries = entries == null ? null : Collections.unmodifiableMap(entries);
		this.generation++;
	}

	/** Discards the index, so lookups fall back to searching. */
	public void clear() { replace(null); }

	/** Returns whether anything has been indexed. */
	public boolean isPresent() { return entries != null; }

	/**
	 * Returns how many times this index has been replaced.
	 *
	 * @return the current generation
	 */
	public long getGeneration() { return generation; }

	/**
	 * Returns how many identifiers are indexed, or {@code -1} when there is no
	 * index.
	 *
	 * @return the number of entries, or {@code -1}
	 */
	public int size() {
		Map<String, File> current = entries;
		return current == null ? -1 : current.size();
	}
}
