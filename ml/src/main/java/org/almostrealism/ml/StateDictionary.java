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

package org.almostrealism.ml;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.persistence.AssetGroup;
import org.almostrealism.persistence.AssetGroupInfo;
import org.almostrealism.persistence.CollectionEncoder;
import org.almostrealism.protobuf.Collections;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link StateDictionary} provides access to model weights stored in protobuf format.
 * <p>
 * It reads {@link org.almostrealism.protobuf.Collections.CollectionLibraryData}
 * from binary protobuf files from a directory and uses {@link CollectionEncoder}
 * to decode them into {@link PackedCollection}s.
 *
 * @author  Michael Murray
 */
public class StateDictionary extends AssetGroup implements Destroyable, ConsoleFeatures {
	private Map<String, PackedCollection<?>> weights;

	/**
	 * Create a {@link StateDictionary} by loading weights from the specified directory.
	 *
	 * @param weightsDirectory Directory containing protobuf weight files
	 * @throws IOException if files cannot be read or parsed
	 */
	public StateDictionary(String weightsDirectory) throws IOException {
		super(weightsDirectory);
		init();
	}

	/**
	 * Create a {@link StateDictionary} by loading weights identified by an {@link AssetGroupInfo}.
	 *
	 * @param assets AssetGroupInfo containing the directory and other metadata.
	 * @throws IOException  if the assets cannot be obtained, read or parsed
	 */
	public StateDictionary(AssetGroupInfo assets) throws IOException {
		super(assets);
		init();
	}

	protected void init() throws IOException {
		this.weights = new HashMap<>();
		loadWeights();
	}

	/**
	 * Load weights from protobuf files in the directory.
	 */
	private void loadWeights() throws IOException {
		int total = files()
				.filter(File::exists)
				.filter(f -> !f.getName().startsWith("."))
				.mapToInt(weightFile -> {
			// Read and parse protobuf
			try (FileInputStream fis = new FileInputStream(weightFile)) {
				Collections.CollectionLibraryData libraryData = Collections.CollectionLibraryData.parseFrom(fis);

				// Decode each collection entry
				for (Collections.CollectionLibraryEntry entry : libraryData.getCollectionsList()) {
					String key = entry.getKey();
					PackedCollection<?> collection = CollectionEncoder.decode(entry.getCollection());

					if (collection != null) {
						weights.put(key, collection);
					}
				}

				System.out.println("Loaded " + libraryData.getCollectionsCount() +
						" weight tensors from " + weightFile.getName());
				return 1;
			} catch (IOException e) {
				warn("Error reading weights from file " + weightFile.getName() + ": " + e.getMessage());
				return 0;
			}
		}).sum();

		System.out.println("StateDictionary loaded " + weights.size() +
				" total weight tensors from " + total + " protobuf files");
	}

	/**
	 * Get a weight by key.
	 *
	 * @param key Weight key
	 * @return PackedCollection containing the weight data, or null if not found
	 */
	public PackedCollection<?> get(String key) {
		return weights.get(key);
	}

	/**
	 * Check if a weight exists for the given key.
	 *
	 * @param key Weight key
	 * @return true if weight exists, false otherwise
	 */
	public boolean containsKey(String key) {
		return weights.containsKey(key);
	}

	/**
	 * Get all weight keys.
	 *
	 * @return Set of all weight keys
	 */
	public Set<String> keySet() {
		return weights.keySet();
	}

	/**
	 * Get the number of loaded weights.
	 *
	 * @return Number of weights
	 */
	public int size() {
		return weights.size();
	}

	/**
	 * Get all weights as a map (for compatibility with existing code).
	 *
	 * @return Map of all weights
	 */
	public Map<String, PackedCollection<?>> getAllWeights() {
		return new HashMap<>(weights);
	}

	/**
	 * Destroy all loaded weight data.
	 *
	 * @see PackedCollection#destroy()
	 */
	@Override
	public void destroy() {
		if (weights != null) {
			weights.values().forEach(PackedCollection::destroy);
			weights.clear();
			weights = null;
		}
	}
}