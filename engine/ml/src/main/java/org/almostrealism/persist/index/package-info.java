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

/**
 * Persistent vector index infrastructure for approximate nearest-neighbor search.
 *
 * <p>This package provides an HNSW (Hierarchical Navigable Small World) index
 * ({@link org.almostrealism.persist.index.HnswIndex}) backed by a protobuf disk
 * store ({@link org.almostrealism.persist.index.ProtobufDiskStore}) for scalable
 * similarity search over high-dimensional embedding vectors.</p>
 *
 * <p>Search results are returned as {@link org.almostrealism.persist.index.SearchResult}
 * instances. The similarity metric is abstracted by
 * {@link org.almostrealism.persist.index.SimilarityMetric}.</p>
 */
package org.almostrealism.persist.index;
