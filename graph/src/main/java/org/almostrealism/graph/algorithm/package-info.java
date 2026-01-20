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

/**
 * Graph algorithms that work with any {@link io.almostrealism.relation.IndexedGraph}.
 *
 * <p>This package provides general-purpose graph algorithms via the Features pattern:</p>
 * <ul>
 *   <li>{@link org.almostrealism.graph.algorithm.CentralityFeatures} - PageRank, betweenness, degree centrality</li>
 *   <li>{@link org.almostrealism.graph.algorithm.CommunityFeatures} - Louvain clustering, modularity</li>
 *   <li>{@link org.almostrealism.graph.algorithm.TraversalFeatures} - Shortest path, BFS, personalized PageRank</li>
 *   <li>{@link org.almostrealism.graph.algorithm.GraphFeatures} - All of the above combined</li>
 * </ul>
 *
 * <p>These algorithms are designed to be domain-agnostic and work with any graph
 * implementation, including audio similarity graphs, 3D meshes, molecular structures,
 * and neural network topologies.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class MyAnalyzer implements GraphFeatures {
 *     public void analyze(IndexedGraph<?> graph) {
 *         // Compute centrality
 *         double[] ranks = pageRank(graph, 0.85, 50);
 *
 *         // Detect communities
 *         int[] clusters = louvain(graph, 1.0);
 *
 *         // Find paths
 *         List<Integer> path = shortestPath(graph, source, target);
 *     }
 * }
 * }</pre>
 *
 * @see io.almostrealism.relation.IndexedGraph
 * @see io.almostrealism.relation.WeightedGraph
 * @see io.almostrealism.relation.Graph
 */
package org.almostrealism.graph.algorithm;
