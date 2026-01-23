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
import org.almostrealism.persistence.Asset;
import org.almostrealism.persistence.AssetGroup;
import org.almostrealism.persistence.AssetGroupInfo;
import org.almostrealism.persistence.CollectionEncoder;
import org.almostrealism.protobuf.Collections;

import io.almostrealism.code.Precision;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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
	private Map<String, PackedCollection> weights;

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
	 * @param assets AssetGroupInfo pointing to the weights and metadata.
	 * @throws IOException  if the assets cannot be obtained, read or parsed
	 */
	public StateDictionary(AssetGroupInfo assets) throws IOException {
		super(assets);
		init();
	}

	/**
	 * Create a {@link StateDictionary} by loading weights from provided {@link Asset}s.
	 *
	 * @param assets Assets containing the weights.
	 * @throws IOException  if the assets cannot be read or parsed
	 */
	public StateDictionary(List<Asset> assets) throws IOException {
		super(assets);
		init();
	}

	/**
	 * Create a {@link StateDictionary} with manually provided weights (for testing).
	 *
	 * @param weights Map of weight names to PackedCollections
	 */
	public StateDictionary(Map<String, PackedCollection> weights) {
		this.weights = weights;
	}

	protected void init() throws IOException {
		this.weights = new HashMap<>();
		loadWeights();
	}

	/**
	 * Load weights from protobuf {@link org.almostrealism.persistence.Asset}s.
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
					PackedCollection collection = CollectionEncoder.decode(entry.getCollection());

					if (collection != null) {
						weights.put(key, collection);
					}
				}

				System.out.println("Loaded " + libraryData.getCollectionsCount() +
						" weight tensors from " + weightFile.getName());
				return 1;
			} catch (Exception e) {
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
	public PackedCollection get(String key) {
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
	public Map<String, PackedCollection> getAllWeights() {
		return new HashMap<>(weights);
	}

	/**
	 * Add or replace a weight in this dictionary.
	 *
	 * @param key Weight key
	 * @param weight PackedCollection containing the weight data
	 */
	public void put(String key, PackedCollection weight) {
		if (weights == null) {
			weights = new HashMap<>();
		}
		weights.put(key, weight);
	}

	/**
	 * Save all weights to a single protobuf file.
	 *
	 * @param outputPath Path to write the weights file
	 * @throws IOException if writing fails
	 */
	public void save(Path outputPath) throws IOException {
		save(outputPath, Precision.FP32);
	}

	/**
	 * Save all weights to a single protobuf file with specified precision.
	 *
	 * @param outputPath Path to write the weights file
	 * @param precision Precision for encoding (FP32 or FP64)
	 * @throws IOException if writing fails
	 */
	public void save(Path outputPath, Precision precision) throws IOException {
		Collections.CollectionLibraryData libraryData = encode(weights, precision);
		try (OutputStream out = Files.newOutputStream(outputPath)) {
			libraryData.writeTo(out);
		}
	}

	/**
	 * Encode a map of weights to protobuf format.
	 *
	 * @param weights Map of weight names to PackedCollections
	 * @return Encoded protobuf data
	 */
	public static Collections.CollectionLibraryData encode(Map<String, PackedCollection> weights) {
		return encode(weights, Precision.FP32);
	}

	/**
	 * Encode a map of weights to protobuf format with specified precision.
	 *
	 * @param weights Map of weight names to PackedCollections
	 * @param precision Precision for encoding (FP32 or FP64)
	 * @return Encoded protobuf data
	 */
	public static Collections.CollectionLibraryData encode(Map<String, PackedCollection> weights, Precision precision) {
		Collections.CollectionLibraryData.Builder libraryBuilder = Collections.CollectionLibraryData.newBuilder();

		for (Map.Entry<String, PackedCollection> entry : weights.entrySet()) {
			Collections.CollectionLibraryEntry libraryEntry = Collections.CollectionLibraryEntry.newBuilder()
					.setKey(entry.getKey())
					.setCollection(CollectionEncoder.encode(entry.getValue(), precision))
					.build();
			libraryBuilder.addCollections(libraryEntry);
		}

		return libraryBuilder.build();
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