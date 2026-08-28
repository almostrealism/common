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
 *
 * <h2>Collection Data Memory</h2>
 *
 * <p>Weight tensors can be served to the compute stack directly from their protobuf
 * encoding rather than being materialized on the Java heap first:</p>
 * <ul>
 *   <li>{@link org.almostrealism.persist.assets.CollectionDataMemoryProvider} -
 *       the shared {@code "PROTOBUF"} {@link io.almostrealism.code.MemoryProvider}
 *       that exposes collection data as read-only memory. It is a source provider:
 *       the default {@code allocate(int)} rejects empty allocation (memory is
 *       created only from existing messages or file references), and the default
 *       {@code setMem(...)} rejects writes — migration to a device is one-way.</li>
 *   <li>{@link org.almostrealism.persist.assets.CollectionDataMemory} - the
 *       abstract {@link io.almostrealism.code.Memory} implementation that
 *       {@link CollectionDataMemoryProvider} returns.</li>
 *   <li>{@link org.almostrealism.persist.assets.ParsedCollectionDataMemory} -
 *       memory backed by a {@link org.almostrealism.protobuf.Collections.CollectionData}
 *       already on the heap. Skips the device allocation when no kernel ever
 *       reads the values.</li>
 *   <li>{@link org.almostrealism.persist.assets.MappedCollectionDataMemory} -
 *       memory backed by values still in the file they were written to. The
 *       values are read through a
 *       {@link org.almostrealism.hardware.mem.FileMapping} of the file when a
 *       kernel first needs them, so a tensor no kernel ever asks for occupies
 *       neither the Java heap nor a device.</li>
 *   <li>{@link org.almostrealism.persist.assets.CollectionDataReference} -
 *       locates collection data within a message by descending a path of field
 *       numbers, and reports the byte range and precision of its values
 *       without parsing them. A {@link MappedCollectionDataMemory} is built on
 *       top of one of these.</li>
 *   <li>{@link org.almostrealism.persist.assets.EncodedMessage} - the bytes of
 *       a protobuf message with field positions locatable without decoding.
 *       {@link CollectionDataReference} walks one of these to find what it
 *       needs.</li>
 * </ul>
 *
 * <p>The {@code CollectionDataMemoryProvider} memory is consulted on first kernel
 * use and migrates to the device lazily, so {@link org.almostrealism.ml.StateDictionary}
 * weight tensors that nothing uses cost nothing.</p>
 */
package org.almostrealism.persist.assets;
