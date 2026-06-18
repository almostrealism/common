/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.persistence;

import org.almostrealism.audio.api.Audio;
import org.almostrealism.persist.index.ProtobufDiskStore;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Persistence for {@link Audio.AudioLayerGroup} records, the slim sibling of
 * {@link ProtobufWaveDetailsStore}.
 *
 * <p>Backed by a {@link ProtobufDiskStore} parameterised on
 * {@link Audio.AudioLayerGroup#parser()} and keyed by the group
 * {@link Audio.AudioLayerGroup#getKey() key}. Unlike the details store this
 * one never initialises an HNSW vector index — group similarity is out of
 * scope and records carry only group-level metadata plus, per audio layer, an
 * {@code audio_ref} pointing at the matching {@link Audio.WaveDetailData} in
 * the main details index.</p>
 *
 * <p>Records are expected to be small (no inline audio buffers), so the store
 * lives in its own {@code groups/} subdirectory next to the details store and
 * is loaded eagerly for tree display via {@link #allGroups()}.</p>
 *
 * @see ProtobufWaveDetailsStore
 * @see ProtobufDiskStore
 */
public class ProtobufLayerGroupStore implements Closeable {

	/** The underlying disk store handling batched serialization and index management. */
	private final ProtobufDiskStore<Audio.AudioLayerGroup> diskStore;

	/**
	 * Creates a store at the given directory with default settings.
	 *
	 * @param rootDir directory for batch files and index
	 */
	public ProtobufLayerGroupStore(File rootDir) {
		this.diskStore = new ProtobufDiskStore<>(rootDir, Audio.AudioLayerGroup.parser());
	}

	/**
	 * Returns the underlying {@link ProtobufDiskStore} for direct access to
	 * store-level operations.
	 *
	 * @return the backing disk store
	 */
	public ProtobufDiskStore<Audio.AudioLayerGroup> getDiskStore() {
		return diskStore;
	}

	/**
	 * Stores a group keyed by its {@link Audio.AudioLayerGroup#getKey() key},
	 * replacing any existing record with the same key.
	 *
	 * @param group the group to store
	 */
	public void put(Audio.AudioLayerGroup group) {
		diskStore.put(group.getKey(), group);
	}

	/**
	 * Retrieves a group by key.
	 *
	 * @param key the group key
	 * @return the group, or {@code null} if not found
	 */
	public Audio.AudioLayerGroup get(String key) {
		return diskStore.get(key);
	}

	/**
	 * Removes the group with the given key, if present.
	 *
	 * @param key the group key
	 */
	public void delete(String key) {
		diskStore.delete(key);
	}

	/**
	 * Checks whether a group with the given key exists.
	 *
	 * @param key the group key
	 * @return {@code true} if a record exists for this key
	 */
	public boolean containsKey(String key) {
		return diskStore.containsKey(key);
	}

	/**
	 * Returns all stored group keys.
	 *
	 * @return an unmodifiable set of all group keys
	 */
	public Set<String> allKeys() {
		return diskStore.allIds();
	}

	/**
	 * Loads every stored group. Intended for building the library tree's
	 * group nodes at display time.
	 *
	 * @return all stored groups, in no particular order
	 */
	public List<Audio.AudioLayerGroup> allGroups() {
		List<Audio.AudioLayerGroup> groups = new ArrayList<>();
		diskStore.scan(groups::add);
		return groups;
	}

	/**
	 * Returns the number of stored groups.
	 *
	 * @return group count
	 */
	public int size() {
		return diskStore.size();
	}

	/**
	 * Flushes pending data to disk without closing the store.
	 */
	public void flush() {
		diskStore.flush();
	}

	/**
	 * Closes the store, flushing any pending data and releasing resources.
	 */
	@Override
	public void close() {
		diskStore.close();
	}
}
