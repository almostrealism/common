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

package org.almostrealism.audio.similarity;

import io.almostrealism.relation.IndexedGraph;
import org.almostrealism.audio.data.WaveDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Adapts a collection of audio similarity data to the {@link IndexedGraph} interface
 * for use with general-purpose graph algorithms.
 *
 * <p>This adapter treats audio samples as graph nodes and their computed similarity
 * scores as edge weights. It enables applying graph algorithms like PageRank,
 * community detection, and path finding to discover relationships between audio samples.</p>
 *
 * <p>Nodes are stored as lightweight {@link SimilarityNode} instances that carry only
 * the content identifier and similarity map, not the full {@link WaveDetails} with its
 * heavy feature vectors. This allows the {@link org.almostrealism.audio.AudioLibrary}
 * cache to evict feature data while the graph algorithms run.</p>
 *
 * <h2>Graph Structure</h2>
 * <ul>
 *   <li><b>Nodes</b>: Each {@link SimilarityNode} represents an audio sample</li>
 *   <li><b>Edges</b>: Similarity scores between samples (symmetric)</li>
 *   <li><b>Weights</b>: Higher weight = more similar</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class MyAnalyzer implements GraphFeatures {
 *     public void analyze(AudioSimilarityGraph graph) {
 *         double[] ranks = pageRank(graph, 0.85, 50);
 *         int[] communities = louvain(graph, 1.0);
 *
 *         int centralIdx = argmax(ranks);
 *         String identifier = graph.nodeAt(centralIdx).getIdentifier();
 *     }
 * }
 * }</pre>
 *
 * @see IndexedGraph
 * @see SimilarityNode
 *
 * @author Michael Murray
 */
public class AudioSimilarityGraph implements IndexedGraph<SimilarityNode> {

	/** Ordered list of similarity nodes in the graph (one per audio sample). */
	private final List<SimilarityNode> nodes;

	/** Mapping from audio identifier to node index for O(1) index lookup. */
	private final Map<String, Integer> nodeIndex;

	/** Mapping from audio identifier to node for direct node retrieval. */
	private final Map<String, SimilarityNode> nodeLookup;

	/** Minimum similarity score for an edge to be included in the graph. */
	private final double threshold;

	/**
	 * Creates a new AudioSimilarityGraph from the given details with no similarity threshold.
	 *
	 * <p>Only the identifier and similarity map are retained from each {@link WaveDetails};
	 * the heavy feature data is not referenced by the graph.</p>
	 *
	 * @param details collection of WaveDetails with pre-computed similarities
	 */
	public AudioSimilarityGraph(Collection<WaveDetails> details) {
		this(details, 0.0);
	}

	/**
	 * Creates a new AudioSimilarityGraph from a collection of WaveDetails with a similarity threshold.
	 *
	 * @param details   collection of WaveDetails with pre-computed similarities
	 * @param threshold minimum similarity to include as an edge (0.0 = include all)
	 */
	public AudioSimilarityGraph(Collection<WaveDetails> details, double threshold) {
		this.threshold = threshold;
		this.nodes = new ArrayList<>(details.size());
		this.nodeIndex = new HashMap<>();
		this.nodeLookup = new HashMap<>();

		for (WaveDetails d : details) {
			if (d.getIdentifier() != null) {
				SimilarityNode node = new SimilarityNode(
						d.getIdentifier(), d.getSimilarities());
				int idx = nodes.size();
				nodes.add(node);
				nodeIndex.put(d.getIdentifier(), idx);
				nodeLookup.put(d.getIdentifier(), node);
			}
		}
	}

	/**
	 * Internal constructor for creating a derived graph with pre-built data structures.
	 *
	 * @param nodes       ordered list of similarity nodes
	 * @param nodeIndex   map from identifier to node index
	 * @param nodeLookup  map from identifier to node
	 * @param threshold   minimum similarity for edge inclusion
	 */
	private AudioSimilarityGraph(List<SimilarityNode> nodes,
								 Map<String, Integer> nodeIndex,
								 Map<String, SimilarityNode> nodeLookup,
								 double threshold) {
		this.nodes = nodes;
		this.nodeIndex = nodeIndex;
		this.nodeLookup = nodeLookup;
		this.threshold = threshold;
	}

	/**
	 * Creates a copy of this graph with a similarity threshold applied.
	 *
	 * @param threshold minimum similarity to include as an edge
	 * @return a new AudioSimilarityGraph with the threshold
	 */
	public AudioSimilarityGraph withThreshold(double threshold) {
		return new AudioSimilarityGraph(nodes, nodeIndex, nodeLookup, threshold);
	}

	/**
	 * Returns the similarity threshold for edge inclusion.
	 *
	 * @return the threshold (0.0 = no filtering)
	 */
	public double getThreshold() {
		return threshold;
	}

	@Override
	public SimilarityNode nodeAt(int index) {
		if (index < 0 || index >= nodes.size()) {
			return null;
		}
		return nodes.get(index);
	}

	@Override
	public int indexOf(SimilarityNode node) {
		if (node == null || node.getIdentifier() == null) {
			return -1;
		}
		return nodeIndex.getOrDefault(node.getIdentifier(), -1);
	}

	@Override
	public Collection<SimilarityNode> neighbors(SimilarityNode node) {
		if (node == null) return List.of();

		Map<String, Double> similarities = node.getSimilarities();
		if (similarities == null || similarities.isEmpty()) {
			return List.of();
		}

		return similarities.entrySet().stream()
				.filter(e -> e.getValue() > threshold)
				.map(e -> nodeLookup.get(e.getKey()))
				.filter(n -> n != null)
				.collect(Collectors.toList());
	}

	@Override
	public int countNodes() {
		return nodes.size();
	}

	@Override
	public double edgeWeight(SimilarityNode from, SimilarityNode to) {
		if (from == null || to == null) return 0.0;
		if (from.getIdentifier() == null || to.getIdentifier() == null) return 0.0;

		Map<String, Double> similarities = from.getSimilarities();
		if (similarities == null) return 0.0;

		Double weight = similarities.get(to.getIdentifier());
		if (weight == null || weight <= threshold) {
			return 0.0;
		}
		return weight;
	}

	@Override
	public Stream<SimilarityNode> children() {
		return nodes.stream();
	}

	@Override
	public List<Integer> neighborIndices(int nodeIndex) {
		if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
			return List.of();
		}

		SimilarityNode node = nodes.get(nodeIndex);
		Map<String, Double> similarities = node.getSimilarities();
		if (similarities == null || similarities.isEmpty()) {
			return List.of();
		}

		List<Integer> result = new ArrayList<>();
		for (Map.Entry<String, Double> entry : similarities.entrySet()) {
			if (entry.getValue() > threshold) {
				Integer idx = this.nodeIndex.get(entry.getKey());
				if (idx != null) {
					result.add(idx);
				}
			}
		}
		return result;
	}

	@Override
	public double edgeWeight(int fromIndex, int toIndex) {
		if (fromIndex < 0 || fromIndex >= nodes.size()) return 0.0;
		if (toIndex < 0 || toIndex >= nodes.size()) return 0.0;

		SimilarityNode from = nodes.get(fromIndex);
		SimilarityNode to = nodes.get(toIndex);

		return edgeWeight(from, to);
	}

	@Override
	public double weightedDegree(SimilarityNode node) {
		if (node == null) return 0.0;

		Map<String, Double> similarities = node.getSimilarities();
		if (similarities == null) return 0.0;

		return similarities.values().stream()
				.filter(v -> v > threshold)
				.mapToDouble(Double::doubleValue)
				.sum();
	}
}
