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
 * Adapts a collection of {@link WaveDetails} to the {@link IndexedGraph} interface
 * for use with general-purpose graph algorithms.
 *
 * <p>This adapter treats audio samples as graph nodes and their computed similarity
 * scores as edge weights. It enables applying graph algorithms like PageRank,
 * community detection, and path finding to discover relationships between audio samples.</p>
 *
 * <h2>Graph Structure</h2>
 * <ul>
 *   <li><b>Nodes</b>: Each {@link WaveDetails} is a node</li>
 *   <li><b>Edges</b>: Similarity scores between samples (symmetric)</li>
 *   <li><b>Weights</b>: Higher weight = more similar</li>
 * </ul>
 *
 * <h2>Similarity Threshold</h2>
 * <p>By default, all non-zero similarity scores create edges. Use {@link #withThreshold(double)}
 * to create a filtered graph that only includes edges above a certain similarity threshold.
 * This can improve algorithm performance and focus on stronger relationships.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class MyAnalyzer implements GraphFeatures {
 *     public void analyze(Collection<WaveDetails> details) {
 *         // Create graph from details
 *         AudioSimilarityGraph graph = new AudioSimilarityGraph(details);
 *
 *         // Apply graph algorithms
 *         double[] ranks = pageRank(graph, 0.85, 50);
 *         int[] communities = louvain(graph, 1.0);
 *
 *         // Get most central sample
 *         int centralIdx = argmax(ranks);
 *         WaveDetails centralSample = graph.nodeAt(centralIdx);
 *     }
 * }
 * }</pre>
 *
 * <h2>Weight Interpretation</h2>
 * <p>Similarity scores are used directly as edge weights (higher = more similar).
 * This works naturally with PageRank and community detection algorithms. For
 * shortest-path algorithms that treat weight as distance, transform the weights:</p>
 * <pre>{@code
 * // Option 1: Invert similarity (use for path finding)
 * double distance = 1.0 / similarity;
 *
 * // Option 2: Use dissimilarity
 * double distance = maxSimilarity - similarity;
 * }</pre>
 *
 * @see IndexedGraph
 * @see WaveDetails
 *
 * @author Michael Murray
 */
public class AudioSimilarityGraph implements IndexedGraph<WaveDetails> {

	private final List<WaveDetails> nodes;
	private final Map<String, Integer> nodeIndex;
	private final Map<String, WaveDetails> detailsLookup;
	private final double threshold;

	/**
	 * Creates a new AudioSimilarityGraph from the given details with no similarity threshold.
	 *
	 * @param details collection of WaveDetails with pre-computed similarities
	 */
	public AudioSimilarityGraph(Collection<WaveDetails> details) {
		this(details, 0.0);
	}

	/**
	 * Creates a new AudioSimilarityGraph from a collection of WaveDetails with a similarity threshold.
	 *
	 * <p>Only edges with similarity above the threshold will be included in the graph.
	 * Use this to focus on stronger relationships and improve algorithm performance.</p>
	 *
	 * @param details collection of WaveDetails with pre-computed similarities
	 * @param threshold minimum similarity to include as an edge (0.0 = include all)
	 */
	public AudioSimilarityGraph(Collection<WaveDetails> details, double threshold) {
		this.threshold = threshold;
		this.nodes = new ArrayList<>(details);
		this.nodeIndex = new HashMap<>();
		this.detailsLookup = new HashMap<>();

		for (int i = 0; i < nodes.size(); i++) {
			WaveDetails node = nodes.get(i);
			if (node.getIdentifier() != null) {
				nodeIndex.put(node.getIdentifier(), i);
				detailsLookup.put(node.getIdentifier(), node);
			}
		}
	}

	/**
	 * Creates a copy of this graph with a similarity threshold applied.
	 *
	 * @param threshold minimum similarity to include as an edge
	 * @return a new AudioSimilarityGraph with the threshold
	 */
	public AudioSimilarityGraph withThreshold(double threshold) {
		return new AudioSimilarityGraph(nodes, threshold);
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
	public WaveDetails nodeAt(int index) {
		if (index < 0 || index >= nodes.size()) {
			return null;
		}
		return nodes.get(index);
	}

	@Override
	public int indexOf(WaveDetails node) {
		if (node == null || node.getIdentifier() == null) {
			return -1;
		}
		return nodeIndex.getOrDefault(node.getIdentifier(), -1);
	}

	@Override
	public Collection<WaveDetails> neighbors(WaveDetails node) {
		if (node == null) return List.of();

		Map<String, Double> similarities = node.getSimilarities();
		if (similarities == null || similarities.isEmpty()) {
			return List.of();
		}

		return similarities.entrySet().stream()
				.filter(e -> e.getValue() > threshold)
				.map(e -> getDetails(e.getKey()))
				.filter(d -> d != null)
				.collect(Collectors.toList());
	}

	private WaveDetails getDetails(String identifier) {
		return detailsLookup.get(identifier);
	}

	@Override
	public int countNodes() {
		return nodes.size();
	}

	@Override
	public double edgeWeight(WaveDetails from, WaveDetails to) {
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
	public Stream<WaveDetails> children() {
		return nodes.stream();
	}

	@Override
	public List<Integer> neighborIndices(int nodeIndex) {
		if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
			return List.of();
		}

		WaveDetails node = nodes.get(nodeIndex);
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

		WaveDetails from = nodes.get(fromIndex);
		WaveDetails to = nodes.get(toIndex);

		return edgeWeight(from, to);
	}

	@Override
	public double weightedDegree(WaveDetails node) {
		if (node == null) return 0.0;

		Map<String, Double> similarities = node.getSimilarities();
		if (similarities == null) return 0.0;

		return similarities.values().stream()
				.filter(v -> v > threshold)
				.mapToDouble(Double::doubleValue)
				.sum();
	}
}
