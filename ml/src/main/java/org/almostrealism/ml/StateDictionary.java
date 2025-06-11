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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persistence.CollectionEncoder;
import org.almostrealism.protobuf.Collections;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * StateDictionary provides access to model weights stored in protobuf format.
 * It reads CollectionLibraryData from binary protobuf files named weights_0, weights_1, etc.
 * and uses CollectionEncoder to decode them into PackedCollection objects.
 */
public class StateDictionary {
	private final Map<String, PackedCollection<?>> weights;
	private final String weightsDirectory;

	/**
	 * Create a StateDictionary by loading weights from the specified directory.
	 * Reads files named weights_0, weights_1, weights_2, etc. until no more files are found.
	 *
	 * @param weightsDirectory Directory containing protobuf weight files
	 * @throws IOException if files cannot be read or parsed
	 */
	public StateDictionary(String weightsDirectory) throws IOException {
		this.weightsDirectory = weightsDirectory;
		this.weights = new HashMap<>();
		loadWeightsFromDirectory();
	}

	/**
	 * Load weights from protobuf files in the directory.
	 */
	private void loadWeightsFromDirectory() throws IOException {
		int fileIndex = 0;

		while (true) {
			File weightFile = new File(weightsDirectory, "weights_" + fileIndex);
			if (!weightFile.exists()) {
				break; // No more weight files
			}

			// Read and parse protobuf file
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
			}

			fileIndex++;
		}

		System.out.println("StateDictionary loaded " + weights.size() +
				" total weight tensors from " + fileIndex + " protobuf files");
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
	public java.util.Set<String> keySet() {
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
	 * Get the weights directory path.
	 *
	 * @return Directory path
	 */
	public String getWeightsDirectory() {
		return weightsDirectory;
	}

	/**
	 * Get all weights as a map (for compatibility with existing code).
	 *
	 * @return Map of all weights
	 */
	public Map<String, PackedCollection<?>> getAllWeights() {
		return new HashMap<>(weights);
	}
}