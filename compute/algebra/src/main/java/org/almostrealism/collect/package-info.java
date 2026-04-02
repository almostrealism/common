/*
 * Copyright 2025 Michael Murray
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
 * Core tensor computation API for the Almost Realism framework.
 *
 * <p>This package provides the central abstractions for building hardware-accelerated
 * tensor computations expressed as {@link io.almostrealism.relation.Producer} composition
 * graphs. Key types include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.collect.CollectionFeatures} — the primary mixin interface
 *       providing 200+ factory methods for constructing tensor operations</li>
 *   <li>{@link org.almostrealism.collect.CollectionProducer} — a typed producer of
 *       {@link org.almostrealism.collect.PackedCollection} with fluent operation methods</li>
 *   <li>{@link org.almostrealism.collect.PackedCollection} — a handle to potentially
 *       GPU-resident flat memory storage, the fundamental data container</li>
 * </ul>
 *
 * <p>All computations must be expressed as Producer compositions — never as Java arithmetic
 * loops — to enable compilation to native GPU/CPU kernels via the hardware backend.</p>
 */
package org.almostrealism.collect;
