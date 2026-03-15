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

package org.almostrealism.persist;

import io.almostrealism.code.Precision;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.persistence.CollectionEncoder;
import org.almostrealism.protobuf.Diskstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * In-memory Hierarchical Navigable Small World (HNSW) index for
 * approximate nearest neighbor search over {@link PackedCollection} vectors.
 *
 * <p>The index stores only IDs and vectors — not full records. When a
 * search returns top-K candidate IDs, the caller fetches full records
 * from the backing store.</p>
 *
 * <p>The graph is persisted to a binary file using protobuf
 * {@link CollectionEncoder} for vector serialization and reloaded on
 * startup so it survives JVM restarts.</p>
 *
 * @see SimilarityMetric
 */
public class HnswIndex {
	private static final Logger log = Logger.getLogger(HnswIndex.class.getName());

	/** Default maximum number of connections per node per layer. */
	public static final int DEFAULT_M = 16;

	/** Default size of the dynamic candidate list during construction. */
	public static final int DEFAULT_EF_CONSTRUCTION = 200;

	/** Default size of the dynamic candidate list during search. */
	public static final int DEFAULT_EF_SEARCH = 50;

	private final int dimension;
	private final int m;
	private final int maxM0;
	private final int efConstruction;
	private int efSearch;
	private final SimilarityMetric metric;
	private final double levelMultiplier;
	private final Random random;

	private final Map<String, Node> nodes;
	private String entryPointId;
	private int maxLevel;

	/**
	 * Create an empty HNSW index.
	 *
	 * @param dimension      vector dimensionality
	 * @param m              max connections per node per layer
	 * @param efConstruction construction candidate list size
	 * @param metric         similarity metric
	 */
	public HnswIndex(int dimension, int m, int efConstruction, SimilarityMetric metric) {
		this.dimension = dimension;
		this.m = m;
		this.maxM0 = 2 * m;
		this.efConstruction = efConstruction;
		this.efSearch = DEFAULT_EF_SEARCH;
		this.metric = metric;
		this.levelMultiplier = 1.0 / Math.log(m);
		this.random = new Random();
		this.nodes = new HashMap<>();
		this.entryPointId = null;
		this.maxLevel = -1;
	}

	/**
	 * Create an empty HNSW index with default parameters and cosine similarity.
	 *
	 * @param dimension vector dimensionality
	 */
	public HnswIndex(int dimension) {
		this(dimension, DEFAULT_M, DEFAULT_EF_CONSTRUCTION, SimilarityMetric.COSINE);
	}

	/**
	 * Set the search candidate list size. Higher values give better recall
	 * at the cost of slower queries.
	 *
	 * @param efSearch candidate list size for search
	 */
	public void setEfSearch(int efSearch) {
		this.efSearch = efSearch;
	}

	/**
	 * Return the number of (non-deleted) nodes in the index.
	 *
	 * @return active node count
	 */
	public int size() {
		int count = 0;
		for (Node node : nodes.values()) {
			if (!node.deleted) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Return the total number of nodes including deleted ones.
	 *
	 * @return total node count
	 */
	public int totalSize() {
		return nodes.size();
	}

	/**
	 * Insert a node into the HNSW graph. The vector is normalized
	 * according to the configured similarity metric before insertion.
	 *
	 * <p>If a node with the same ID already exists, it is replaced.</p>
	 *
	 * @param id     unique identifier
	 * @param vector {@link PackedCollection} vector of the configured dimension
	 * @throws IllegalArgumentException if vector dimension does not match
	 */
	public void insert(String id, PackedCollection vector) {
		if (vector.getMemLength() != dimension) {
			throw new IllegalArgumentException(
					"Expected dimension " + dimension + " but got " + vector.getMemLength());
		}

		double[] normalizedData = metric.normalizeToArray(vector);

		Node existing = nodes.get(id);
		if (existing != null) {
			existing.cachedData = normalizedData;
			existing.deleted = false;
			return;
		}

		int level = randomLevel();
		Node newNode = new Node(id, normalizedData, level);
		nodes.put(id, newNode);

		if (entryPointId == null) {
			entryPointId = id;
			maxLevel = level;
			return;
		}

		String currentId = entryPointId;
		double[] cachedNorm = newNode.cachedData;

		for (int lc = maxLevel; lc > level; lc--) {
			currentId = greedyClosest(currentId, cachedNorm, lc);
		}

		for (int lc = Math.min(level, maxLevel); lc >= 0; lc--) {
			List<String> candidates = searchLayer(currentId, cachedNorm,
					efConstruction, lc);

			int maxConnections = (lc == 0) ? maxM0 : m;
			List<String> neighbors = selectNeighbors(candidates, cachedNorm,
					maxConnections);

			newNode.setNeighbors(lc, neighbors);

			for (String neighborId : neighbors) {
				Node neighbor = nodes.get(neighborId);
				if (neighbor == null) continue;

				List<String> nNeighbors = new ArrayList<>(neighbor.getNeighbors(lc));
				nNeighbors.add(id);

				if (nNeighbors.size() > maxConnections) {
					nNeighbors = selectNeighbors(nNeighbors,
							neighbor.cachedData, maxConnections);
				}
				neighbor.setNeighbors(lc, nNeighbors);
			}

			if (!candidates.isEmpty()) {
				currentId = candidates.get(0);
			}
		}

		if (level > maxLevel) {
			maxLevel = level;
			entryPointId = id;
		}
	}

	/**
	 * Search the index for the top-K most similar vectors.
	 *
	 * @param queryVector query vector (must match configured dimension)
	 * @param topK        number of results to return
	 * @return list of (id, similarity) pairs ordered by descending similarity
	 */
	public List<IdScore> search(PackedCollection queryVector, int topK) {
		if (entryPointId == null || size() == 0) {
			return new ArrayList<>();
		}

		if (queryVector.getMemLength() != dimension) {
			throw new IllegalArgumentException(
					"Expected dimension " + dimension + " but got " + queryVector.getMemLength());
		}

		double[] queryData = metric.normalizeToArray(queryVector);

		String currentId = entryPointId;

		for (int lc = maxLevel; lc > 0; lc--) {
			currentId = greedyClosest(currentId, queryData, lc);
		}

		int ef = Math.max(efSearch, topK);
		List<String> candidates = searchLayer(currentId, queryData, ef, 0);

		List<IdScore> results = new ArrayList<>();
		for (String candidateId : candidates) {
			Node node = nodes.get(candidateId);
			if (node != null && !node.deleted) {
				float sim = metric.similarityCached(queryData, node.cachedData);
				results.add(new IdScore(candidateId, sim));
			}
		}

		results.sort(Comparator.comparingDouble((IdScore s) -> s.score).reversed());

		if (results.size() > topK) {
			results = results.subList(0, topK);
		}

		return results;
	}

	/**
	 * Mark a node as deleted. The node remains in the graph structure
	 * but is excluded from search results.
	 *
	 * @param id the node identifier
	 */
	public void remove(String id) {
		Node node = nodes.get(id);
		if (node != null) {
			node.deleted = true;
		}
	}

	/**
	 * Check whether the index contains a non-deleted node with the given ID.
	 *
	 * @param id the node identifier
	 * @return true if the node exists and is not deleted
	 */
	public boolean contains(String id) {
		Node node = nodes.get(id);
		return node != null && !node.deleted;
	}

	/**
	 * Save the HNSW index to a binary file. Vectors are serialized
	 * using {@link CollectionEncoder} in FP32 precision.
	 *
	 * @param file path to write
	 */
	public void save(Path file) {
		try (OutputStream os = Files.newOutputStream(file)) {
			Diskstore.HnswIndexData.Builder builder =
					Diskstore.HnswIndexData.newBuilder();
			builder.setDimension(dimension);
			builder.setM(m);
			builder.setEfConstruction(efConstruction);
			builder.setMaxLevel(maxLevel);
			builder.setEntryPointId(entryPointId != null ? entryPointId : "");

			for (Map.Entry<String, Node> entry : nodes.entrySet()) {
				Node node = entry.getValue();
				Diskstore.HnswNodeData.Builder nodeBuilder =
						Diskstore.HnswNodeData.newBuilder();
				nodeBuilder.setId(node.id);
				PackedCollection tempVec =
						new PackedCollection(node.cachedData.length)
								.fill(node.cachedData);
				nodeBuilder.setVector(
						CollectionEncoder.encode(tempVec, Precision.FP32));
				tempVec.destroy();
				nodeBuilder.setLevel(node.level);
				nodeBuilder.setDeleted(node.deleted);

				for (int lc = 0; lc <= node.level; lc++) {
					Diskstore.HnswLayerNeighbors.Builder layerBuilder =
							Diskstore.HnswLayerNeighbors.newBuilder();
					layerBuilder.addAllNeighborIds(node.getNeighbors(lc));
					nodeBuilder.addLayers(layerBuilder);
				}

				builder.addNodes(nodeBuilder);
			}

			builder.build().writeTo(os);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to save HNSW index", e);
		}
	}

	/**
	 * Load an HNSW index from a binary file.
	 *
	 * @param file   path to read
	 * @param metric similarity metric to use
	 * @return the loaded index, or null if the file does not exist
	 */
	public static HnswIndex load(Path file, SimilarityMetric metric) {
		if (!Files.exists(file)) {
			return null;
		}

		try (InputStream is = Files.newInputStream(file)) {
			Diskstore.HnswIndexData data =
					Diskstore.HnswIndexData.parseFrom(is);

			HnswIndex index = new HnswIndex(data.getDimension(), data.getM(),
					data.getEfConstruction(), metric);
			index.maxLevel = data.getMaxLevel();
			index.entryPointId = data.getEntryPointId().isEmpty()
					? null : data.getEntryPointId();

			for (Diskstore.HnswNodeData nodeData : data.getNodesList()) {
				PackedCollection vector =
						CollectionEncoder.decode(nodeData.getVector());
				double[] vectorData = SimilarityMetric.toDoubleArray(vector);
				vector.destroy();
				Node node = new Node(nodeData.getId(), vectorData,
						nodeData.getLevel());
				node.deleted = nodeData.getDeleted();

				for (int lc = 0; lc < nodeData.getLayersCount(); lc++) {
					Diskstore.HnswLayerNeighbors layerNeighbors =
							nodeData.getLayers(lc);
					node.setNeighbors(lc,
							new ArrayList<>(layerNeighbors.getNeighborIdsList()));
				}

				index.nodes.put(nodeData.getId(), node);
			}

			return index;
		} catch (IOException e) {
			log.warning("Failed to load HNSW index, starting fresh: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Greedy search on a single layer to find the closest non-deleted node
	 * to the query vector, starting from the given entry point.
	 */
	private String greedyClosest(String entryId, double[] queryData, int layer) {
		String currentId = entryId;
		float currentSim = similarityTo(currentId, queryData);

		boolean improved = true;
		while (improved) {
			improved = false;
			Node current = nodes.get(currentId);
			if (current == null) break;

			for (String neighborId : current.getNeighbors(layer)) {
				Node neighbor = nodes.get(neighborId);
				if (neighbor == null || neighbor.deleted) continue;

				float neighborSim = metric.similarityCached(queryData,
						neighbor.cachedData);
				if (neighborSim > currentSim) {
					currentId = neighborId;
					currentSim = neighborSim;
					improved = true;
				}
			}
		}

		return currentId;
	}

	/**
	 * Search a single layer starting from the entry point, returning
	 * up to {@code ef} closest candidates.
	 */
	private List<String> searchLayer(String entryId, double[] queryData,
									 int ef, int layer) {
		Set<String> visited = new HashSet<>();
		PriorityQueue<IdScore> candidates = new PriorityQueue<>(
				Comparator.comparingDouble((IdScore s) -> s.score).reversed());
		PriorityQueue<IdScore> results = new PriorityQueue<>(
				Comparator.comparingDouble((IdScore s) -> s.score));

		float entrySim = similarityTo(entryId, queryData);
		candidates.add(new IdScore(entryId, entrySim));
		results.add(new IdScore(entryId, entrySim));
		visited.add(entryId);

		while (!candidates.isEmpty()) {
			IdScore closest = candidates.poll();

			IdScore farthestResult = results.peek();
			if (farthestResult != null && closest.score < farthestResult.score
					&& results.size() >= ef) {
				break;
			}

			Node closestNode = nodes.get(closest.id);
			if (closestNode == null) continue;

			for (String neighborId : closestNode.getNeighbors(layer)) {
				if (visited.contains(neighborId)) continue;
				visited.add(neighborId);

				Node neighbor = nodes.get(neighborId);
				if (neighbor == null) continue;

				float neighborSim = metric.similarityCached(queryData,
						neighbor.cachedData);

				farthestResult = results.peek();
				if (results.size() < ef ||
						(farthestResult != null && neighborSim > farthestResult.score)) {
					candidates.add(new IdScore(neighborId, neighborSim));
					results.add(new IdScore(neighborId, neighborSim));

					if (results.size() > ef) {
						results.poll();
					}
				}
			}
		}

		List<String> resultIds = new ArrayList<>();
		while (!results.isEmpty()) {
			resultIds.add(results.poll().id);
		}
		return resultIds;
	}

	/**
	 * Select the best neighbors from a list of candidates for a node
	 * with the given vector, keeping at most {@code maxConnections}.
	 * Uses the simple heuristic of keeping the most similar candidates.
	 */
	private List<String> selectNeighbors(List<String> candidates,
										 double[] nodeData, int maxConnections) {
		List<IdScore> scored = new ArrayList<>(candidates.size());
		for (String candidateId : candidates) {
			Node candidate = nodes.get(candidateId);
			if (candidate == null || candidate.deleted) continue;
			float sim = metric.similarityCached(nodeData, candidate.cachedData);
			scored.add(new IdScore(candidateId, sim));
		}

		scored.sort(Comparator.comparingDouble((IdScore s) -> s.score).reversed());

		List<String> result = new ArrayList<>(
				Math.min(scored.size(), maxConnections));
		for (int i = 0; i < Math.min(scored.size(), maxConnections); i++) {
			result.add(scored.get(i).id);
		}
		return result;
	}

	private float similarityTo(String nodeId, double[] queryData) {
		Node node = nodes.get(nodeId);
		if (node == null) return Float.NEGATIVE_INFINITY;
		return metric.similarityCached(queryData, node.cachedData);
	}

	private int randomLevel() {
		double r = random.nextDouble();
		int level = (int) (-Math.log(r) * levelMultiplier);
		return Math.max(0, level);
	}

	/**
	 * An ID and similarity score pair, used internally for search results.
	 */
	public static class IdScore {
		/** The node identifier. */
		public final String id;
		/** The similarity score. */
		public final float score;

		/**
		 * Create an ID-score pair.
		 *
		 * @param id    node identifier
		 * @param score similarity score
		 */
		public IdScore(String id, float score) {
			this.id = id;
			this.score = score;
		}
	}

	/**
	 * Internal node representation in the HNSW graph. Stores the vector
	 * as a {@code double[]} for fast similarity computation. No
	 * {@link PackedCollection} is retained, avoiding native memory leaks
	 * when the finalizer is disabled.
	 */
	private static class Node {
		final String id;
		double[] cachedData;
		final int level;
		boolean deleted;
		private final List<List<String>> neighborsByLayer;

		Node(String id, double[] data, int level) {
			this.id = id;
			this.cachedData = data;
			this.level = level;
			this.deleted = false;
			this.neighborsByLayer = new ArrayList<>(level + 1);
			for (int i = 0; i <= level; i++) {
				this.neighborsByLayer.add(new ArrayList<>());
			}
		}

		List<String> getNeighbors(int layer) {
			if (layer >= neighborsByLayer.size()) {
				return new ArrayList<>();
			}
			return neighborsByLayer.get(layer);
		}

		void setNeighbors(int layer, List<String> neighbors) {
			while (neighborsByLayer.size() <= layer) {
				neighborsByLayer.add(new ArrayList<>());
			}
			neighborsByLayer.set(layer, new ArrayList<>(neighbors));
		}
	}
}
