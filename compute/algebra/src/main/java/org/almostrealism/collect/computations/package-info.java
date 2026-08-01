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
 * Concrete producer computation implementations for {@link org.almostrealism.collect.PackedCollection} operations.
 *
 * <p>This package contains the low-level computation nodes that make up the collection
 * producer graph. Each class implements {@link io.almostrealism.relation.Producer} and
 * generates native kernel code via {@code getScope()} for hardware acceleration.</p>
 *
 * <p>Typical operations implemented here include element-wise arithmetic, aggregation,
 * padding, repetition, subset extraction, reshaping, and exponential functions.
 * These are composed by the feature interfaces in {@link org.almostrealism.collect}.</p>
 */
package org.almostrealism.collect.computations;
