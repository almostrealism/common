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

package org.almostrealism.graph.algorithm;

/**
 * Combines all graph algorithm capabilities into a single interface.
 *
 * <p>Implement this interface to gain access to centrality measures,
 * community detection, and graph traversal algorithms.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class PrototypeDiscovery implements GraphFeatures {
 *     public void discover(IndexedGraph<?> graph) {
 *         // Centrality
 *         double[] ranks = pageRank(graph, 0.85, 50);
 *         int central = argmax(ranks);
 *
 *         // Community detection
 *         int[] communities = louvain(graph, 1.0);
 *         double quality = modularity(graph, communities);
 *
 *         // Traversal
 *         List<Integer> path = shortestPath(graph, source, target);
 *     }
 * }
 * }</pre>
 *
 * @see CentralityFeatures
 * @see CommunityFeatures
 * @see TraversalFeatures
 */
public interface GraphFeatures extends TraversalFeatures, CommunityFeatures {
	// TraversalFeatures already extends CentralityFeatures, so all methods are available
}
