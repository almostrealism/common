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
 * Audio similarity graph adapters and utilities.
 *
 * <p>This package provides adapters that expose audio sample relationships
 * as graphs, enabling the use of general-purpose graph algorithms for
 * audio discovery and organization.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.audio.similarity.AudioSimilarityGraph} -
 *       Adapts a collection of {@link org.almostrealism.audio.data.WaveDetails} to
 *       {@link io.almostrealism.relation.IndexedGraph}</li>
 * </ul>
 *
 * <h2>Usage with Graph Algorithms</h2>
 * <pre>{@code
 * AudioLibrary library = ...;
 * AudioSimilarityGraph graph = library.toSimilarityGraph();
 *
 * // Find central samples (prototypes)
 * double[] ranks = GraphCentrality.pageRank(graph, 0.85, 50);
 * List<Integer> topSamples = GraphCentrality.topK(ranks, 10);
 *
 * // Detect clusters of similar sounds
 * int[] communities = CommunityDetection.louvain(graph, 1.0);
 *
 * // Find samples bridging two styles
 * List<Integer> bridges = GraphTraversal.findBridges(graph, sampleA, sampleB, 5);
 * }</pre>
 *
 * @see org.almostrealism.graph.algorithm.GraphCentrality
 * @see org.almostrealism.graph.algorithm.CommunityDetection
 * @see org.almostrealism.graph.algorithm.GraphTraversal
 */
package org.almostrealism.audio.similarity;
