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
 * Asset management infrastructure for loading and providing model weight files.
 *
 * <p>An {@link org.almostrealism.persist.assets.Asset} represents a single weight
 * file (typically a protobuf binary). {@link org.almostrealism.persist.assets.AssetGroup}
 * aggregates multiple assets that together form a complete model weight set, and is the
 * base class for {@link org.almostrealism.ml.StateDictionary}.</p>
 *
 * <p>Assets can be sourced from the local filesystem via
 * {@link org.almostrealism.persist.assets.LocalAssetsProvider} or provided explicitly
 * via {@link org.almostrealism.persist.assets.ExplicitAssetProvider}. Multiple providers
 * can be combined using
 * {@link org.almostrealism.persist.assets.CombinedAssetInfoProvider}.</p>
 *
 * <p>{@link org.almostrealism.persist.assets.CollectionEncoder} handles the
 * serialization and deserialization of {@link org.almostrealism.collect.PackedCollection}
 * tensors to and from protobuf format.</p>
 */
package org.almostrealism.persist.assets;
