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

package org.almostrealism.persist.index;

import io.almostrealism.code.Precision;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Input;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.protobuf.Diskstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * In-memory Hierarchical Navigable Small World (HNSW) index for
 * nearest neighbor search over {@link PackedCollection} vectors.
 *
 * <p>The index stores only IDs and vectors — not full records. When a
 * search returns top-K candidate IDs, the caller fetches full records
 * from the backing store.</p>
 *
 * <p>All vectors live in one contiguous {@code [capacity, dimension]}
 * store, and every similarity computation is issued through evaluables
 * that are compiled once and reused: one that normalizes an incoming
 * vector directly into its row of the store, and one that scores a
 * vector against the entire store in a single dispatch. Insertion and
 * search each perform a fixed number of dispatches regardless of how
 * many comparisons they imply, and all graph decisions are made on the
 * host from the resulting score array.</p>
 *
 * <p>At the scales this index currently serves, scoring against the
 * whole store is a single cheap dispatch, so both construction and
 * search use exact scores rather than walking the graph. The layered
 * graph is still constructed and persisted — from exact neighbors, so
 * its quality is at least that of a walked construction — which leaves
 * a batched graph traversal available as the scoring strategy when
 * store sizes eventually make brute force unattractive.</p>
 *
 * <p>The graph is persisted to a binary file using protobuf
 * {@link CollectionEncoder} for vector serialization and reloaded on
 * startup so it survives JVM restarts.</p>
 *
 * @see SimilarityMetric
 */
public class HnswIndex implements CodeFeatures, Destroyable {
	/** Logger for this class. */
	private static final Logger log = Logger.getLogger(HnswIndex.class.getName());

	/** Default maximum number of connections per node per layer. */
	public static final int DEFAULT_M = 16;

	/** Default size of the dynamic candidate list during construction. */
	public static final int DEFAULT_EF_CONSTRUCTION = 200;

	/** Default size of the dynamic candidate list during search. */
	public static final int DEFAULT_EF_SEARCH = 50;

	/** Initial number of rows allocated for the contiguous vector store. */
	public static final int INITIAL_CAPACITY = 256;

	/** Dimensionality of the vectors stored in this index. */
	private final int dimension;

	/** Maximum number of bi-directional connections per node per non-zero layer. */
	private final int m;

	/** Maximum connections for layer 0 (typically {@code 2 * m}). */
	private final int maxM0;

	/** Candidate list size used during index construction (controls recall vs. build speed). */
	private final int efConstruction;

	/** Candidate list size used during search (controls recall vs. query speed). */
	private int efSearch;

	/** Similarity metric used for distance calculations and vector normalization. */
	private final SimilarityMetric metric;

	/** Normalization constant for random level generation: {@code 1 / ln(m)}. */
	private final double levelMultiplier;

	/** Random number generator for level assignment during insertion. */
	private final Random random;

	/** All nodes in the graph, including soft-deleted ones, keyed by ID. */
	private final Map<String, Node> nodes;

	/** Number of non-deleted nodes currently in the index. */
	private int activeCount;

	/** ID of the current top-level entry point node, or {@code null} if the index is empty. */
	private String entryPointId;

	/** Highest layer index present in the current graph. */
	private int maxLevel;

	/** Contiguous {@code [capacity, dimension]} storage for all node vectors. */
	private PackedCollection vectors;

	/** Fixed destination for the whole-store score dispatch, one score per row. */
	private PackedCollection scoresOut;

	/** Staging buffer holding the normalized query vector during a search. */
	private final PackedCollection queryBuffer;

	/** Number of rows currently allocated for storage in {@link #vectors}. */
	private int capacity;

	/** Number of rows handed out so far, including rows later freed. */
	private int rowCount;

	/** Rows released by hard removal, available for reuse before extending {@link #rowCount}. */
	private final Deque<Integer> freeRows;

	/** Compiled once per index: normalizes an input vector into its destination. */
	private final Evaluable<PackedCollection> normalizeVector;

	/** Compiled once per capacity: scores a vector against every row of the store. */
	private Evaluable<PackedCollection> scoreAll;

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
		this.activeCount = 0;
		this.entryPointId = null;
		this.maxLevel = -1;
		this.capacity = INITIAL_CAPACITY;
		this.rowCount = 0;
		this.freeRows = new ArrayDeque<>();
		this.vectors = new PackedCollection(shape(capacity, dimension));
		this.queryBuffer = new PackedCollection(shape(dimension));
		this.normalizeVector = (Evaluable<PackedCollection>)
				metric.normalize(Input.value(shape(dimension), 0)).get();
		prepareScoreEvaluable();
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
	 * at the cost of slower queries in a graph-walking search. The current
	 * whole-store scoring strategy returns exact results, so this value is
	 * retained for when a graph traversal strategy is in use.
	 *
	 * @param efSearch candidate list size for search
	 */
	public void setEfSearch(int efSearch) {
		this.efSearch = efSearch;
	}

	/**
	 * Return the search candidate list size.
	 *
	 * @return candidate list size for search
	 */
	public int getEfSearch() {
		return efSearch;
	}

	/**
	 * Return the number of (non-deleted) nodes in the index.
	 *
	 * @return active node count
	 */
	public int size() {
		return activeCount;
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
	 * <p>This is a step boundary: the normalized vector is written into
	 * the store by one dispatch, one further dispatch scores it against
	 * every stored vector, and all layer connections are then decided on
	 * the host from that score array.</p>
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

		Node existing = nodes.get(id);
		if (existing != null) {
			if (!existing.deleted) {
				normalizeVector.into(vectors.get(existing.row, shape(dimension))).evaluate(vector);
				return;
			}
			hardRemove(id);
		}

		int level = randomLevel();
		int row = allocateRow();
		normalizeVector.into(vectors.get(row, shape(dimension))).evaluate(vector);

		Node newNode = new Node(id, row, level);
		nodes.put(id, newNode);
		activeCount++;

		if (entryPointId == null) {
			entryPointId = id;
			maxLevel = level;
			return;
		}

		scoreAll.evaluate(vectors, vectors.get(row, shape(dimension)));
		double[] similarities = scoresOut.toArray(0, rowCount);

		for (int lc = Math.min(level, maxLevel); lc >= 0; lc--) {
			int layer = lc;
			int maxConnections = (lc == 0) ? maxM0 : m;

			List<IdScore> neighbors = topByScore(similarities, maxConnections,
					node -> node != newNode && !node.deleted && node.level >= layer);
			newNode.setNeighbors(lc, neighbors);

			for (IdScore edge : neighbors) {
				Node neighbor = nodes.get(edge.id);
				if (neighbor == null) continue;
				neighbor.connect(lc, new IdScore(id, edge.score), maxConnections);
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
	 * <p>This is a step boundary: one dispatch normalizes the query into a
	 * staging buffer, one further dispatch scores it against every stored
	 * vector, and the top results are selected on the host.</p>
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

		normalizeVector.into(queryBuffer).evaluate(queryVector);
		scoreAll.evaluate(vectors, queryBuffer);
		double[] similarities = scoresOut.toArray(0, rowCount);

		return topByScore(similarities, topK, node -> !node.deleted);
	}

	/**
	 * Mark a node as deleted. The node remains in the graph structure
	 * but is excluded from search results.
	 *
	 * @param id the node identifier
	 */
	public void remove(String id) {
		Node node = nodes.get(id);
		if (node != null && !node.deleted) {
			node.deleted = true;
			activeCount--;
		}
	}

	/**
	 * Fully removes a node from the graph, including its entry in the
	 * {@code nodes} map, returning its storage row for reuse. If the node
	 * was the current entry point, scans for a new highest-level
	 * non-deleted node to take its place. Dangling references in other
	 * nodes' adjacency lists are tolerated: consumers of adjacency filter
	 * through the {@code nodes} map.
	 */
	private void hardRemove(String id) {
		Node removed = nodes.remove(id);
		if (removed == null) return;
		if (!removed.deleted) {
			activeCount--;
		}
		freeRows.push(removed.row);
		if (id.equals(entryPointId)) {
			entryPointId = null;
			maxLevel = -1;
			for (Node candidate : nodes.values()) {
				if (candidate.deleted) continue;
				if (candidate.level > maxLevel) {
					maxLevel = candidate.level;
					entryPointId = candidate.id;
				}
			}
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
	 * using {@link CollectionEncoder} in FP32 precision, and edge
	 * similarities are persisted alongside neighbor IDs so pruning
	 * decisions after a reload need no rescoring.
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
				nodeBuilder.setVector(CollectionEncoder.encode(
						vectors.get(node.row, shape(dimension)), Precision.FP32));
				nodeBuilder.setLevel(node.level);
				nodeBuilder.setDeleted(node.deleted);

				for (int lc = 0; lc <= node.level; lc++) {
					Diskstore.HnswLayerNeighbors.Builder layerBuilder =
							Diskstore.HnswLayerNeighbors.newBuilder();
					for (IdScore edge : node.getNeighbors(lc)) {
						layerBuilder.addNeighborIds(edge.id);
						layerBuilder.addNeighborScores(edge.score);
					}
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
	 * Load an HNSW index from a binary file. Edge similarities saved with
	 * the file are restored; edges from files written before scores were
	 * persisted load with an unknown score and are never pruned ahead of
	 * edges whose scores are known.
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
				int row = index.allocateRow();
				PackedCollection decoded =
						CollectionEncoder.decode(nodeData.getVector());
				index.vectors.setFrom(row * data.getDimension(), decoded,
						0, data.getDimension());
				decoded.destroy();

				Node node = new Node(nodeData.getId(), row, nodeData.getLevel());
				node.deleted = nodeData.getDeleted();

				for (int lc = 0; lc < nodeData.getLayersCount(); lc++) {
					Diskstore.HnswLayerNeighbors layerNeighbors =
							nodeData.getLayers(lc);
					List<IdScore> edges = new ArrayList<>(
							layerNeighbors.getNeighborIdsCount());
					for (int i = 0; i < layerNeighbors.getNeighborIdsCount(); i++) {
						float score = i < layerNeighbors.getNeighborScoresCount()
								? layerNeighbors.getNeighborScores(i) : Float.NaN;
						edges.add(new IdScore(layerNeighbors.getNeighborIds(i), score));
					}
					node.setNeighbors(lc, edges);
				}

				index.nodes.put(nodeData.getId(), node);
				if (!node.deleted) {
					index.activeCount++;
				}
			}

			return index;
		} catch (IOException e) {
			log.warning("Failed to load HNSW index, starting fresh: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Selects the highest-scoring nodes that pass the given filter, at most
	 * {@code limit} of them, ordered by descending similarity.
	 *
	 * @param similarities per-row similarity scores, indexed by storage row
	 * @param limit        maximum number of results
	 * @param include      filter deciding which nodes participate
	 * @return the selected (id, similarity) pairs, best first
	 */
	private List<IdScore> topByScore(double[] similarities, int limit,
									 Predicate<Node> include) {
		PriorityQueue<IdScore> best = new PriorityQueue<>(
				Comparator.comparingDouble((IdScore s) -> s.score));

		for (Node node : nodes.values()) {
			if (!include.test(node)) continue;

			float sim = (float) similarities[node.row];
			if (best.size() < limit) {
				best.add(new IdScore(node.id, sim));
			} else if (best.peek().score < sim) {
				best.poll();
				best.add(new IdScore(node.id, sim));
			}
		}

		List<IdScore> result = new ArrayList<>(best);
		result.sort(Comparator.comparingDouble((IdScore s) -> s.score).reversed());
		return result;
	}

	/**
	 * Returns a free storage row, growing the store when none remain.
	 *
	 * @return the allocated row index
	 */
	private int allocateRow() {
		if (!freeRows.isEmpty()) {
			return freeRows.pop();
		}
		if (rowCount == capacity) {
			grow();
		}
		return rowCount++;
	}

	/**
	 * Doubles the capacity of the vector store, copying existing rows into
	 * the new allocation and recompiling the whole-store score evaluable
	 * for the new shape.
	 */
	private void grow() {
		int newCapacity = capacity * 2;
		PackedCollection expanded = new PackedCollection(shape(newCapacity, dimension));
		expanded.setFrom(0, vectors, 0, rowCount * dimension);
		vectors.destroy();
		vectors = expanded;
		capacity = newCapacity;
		prepareScoreEvaluable();
	}

	/**
	 * (Re)creates the whole-store score evaluable and its fixed destination
	 * for the current capacity. The evaluable scores one query vector
	 * against every row of {@link #vectors} in a single dispatch.
	 */
	private void prepareScoreEvaluable() {
		if (scoresOut != null) scoresOut.destroy();
		scoresOut = new PackedCollection(shape(capacity));
		scoreAll = ((Evaluable<PackedCollection>) metric.similarities(
				Input.value(shape(capacity, dimension), 0),
				Input.value(shape(dimension), 1)).get()).into(scoresOut);
	}

	/**
	 * Samples a random level for a new node using the HNSW level distribution.
	 *
	 * <p>The level is drawn as {@code floor(-ln(uniform) * levelMultiplier)},
	 * clamped to a minimum of 0.</p>
	 *
	 * @return The randomly assigned level for the new node
	 */
	private int randomLevel() {
		double r = random.nextDouble();
		int level = (int) (-Math.log(r) * levelMultiplier);
		return Math.max(0, level);
	}

	/**
	 * Releases the native memory backing the vector store, the score
	 * destination, and the query staging buffer.
	 */
	@Override
	public void destroy() {
		vectors.destroy();
		scoresOut.destroy();
		queryBuffer.destroy();
	}

	/**
	 * An ID and similarity score pair, used for search results and for
	 * graph edges, where the retained score lets overflow pruning proceed
	 * without rescoring. A score of {@link Float#NaN} means the similarity
	 * is unknown (an edge loaded from a file that predates score
	 * persistence); unknown edges are never pruned ahead of known ones.
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
	 * Internal node representation in the HNSW graph. The node holds only
	 * the row index of its vector in the index's contiguous store — no
	 * {@link PackedCollection} is retained per node, so the index carries
	 * a fixed number of native allocations regardless of node count.
	 */
	private static class Node {
		/** Unique identifier for this node. */
		final String id;

		/** Row of this node's normalized vector in the contiguous store. */
		final int row;

		/** Highest layer at which this node has edges. */
		final int level;

		/** Whether this node has been soft-deleted and should be excluded from search results. */
		boolean deleted;

		/** Adjacency lists indexed by layer, each holding scored edges for that layer. */
		private final List<List<IdScore>> neighborsByLayer;

		/**
		 * Creates a new node and initializes empty neighbor lists.
		 *
		 * @param id    Unique node identifier
		 * @param row   Row of the node's vector in the contiguous store
		 * @param level Maximum layer index for this node
		 */
		Node(String id, int row, int level) {
			this.id = id;
			this.row = row;
			this.level = level;
			this.deleted = false;
			this.neighborsByLayer = new ArrayList<>(level + 1);
			for (int i = 0; i <= level; i++) {
				this.neighborsByLayer.add(new ArrayList<>());
			}
		}

		/**
		 * Returns the edge list for the given layer, or an empty list if the layer is out of range.
		 *
		 * @param layer The layer index
		 * @return The mutable scored edge list for that layer
		 */
		List<IdScore> getNeighbors(int layer) {
			if (layer >= neighborsByLayer.size()) {
				return new ArrayList<>();
			}
			return neighborsByLayer.get(layer);
		}

		/**
		 * Replaces the edge list for the given layer, extending the adjacency structure if needed.
		 *
		 * @param layer     The layer index to update
		 * @param neighbors The new list of scored edges
		 */
		void setNeighbors(int layer, List<IdScore> neighbors) {
			while (neighborsByLayer.size() <= layer) {
				neighborsByLayer.add(new ArrayList<>());
			}
			neighborsByLayer.set(layer, new ArrayList<>(neighbors));
		}

		/**
		 * Adds an edge at the given layer, pruning the lowest-scored edge
		 * when the list exceeds the connection limit. Edges with unknown
		 * (NaN) scores are never pruned ahead of edges whose scores are
		 * known; if every score is unknown, the newest edge is dropped.
		 *
		 * @param layer          The layer index
		 * @param edge           The scored edge to add
		 * @param maxConnections The connection limit for this layer
		 */
		void connect(int layer, IdScore edge, int maxConnections) {
			while (neighborsByLayer.size() <= layer) {
				neighborsByLayer.add(new ArrayList<>());
			}

			List<IdScore> edges = neighborsByLayer.get(layer);
			edges.add(edge);
			if (edges.size() <= maxConnections) return;

			int drop = -1;
			double lowest = Double.POSITIVE_INFINITY;
			for (int i = 0; i < edges.size(); i++) {
				float score = edges.get(i).score;
				if (!Float.isNaN(score) && score < lowest) {
					lowest = score;
					drop = i;
				}
			}
			edges.remove(drop < 0 ? edges.size() - 1 : drop);
		}
	}
}
