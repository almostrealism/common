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
 * Provides classes for representing relational frames based on Relational Frame Theory (RFT).
 *
 * <p>Relational Frame Theory is a psychological theory of human language and cognition
 * that describes how humans learn to relate stimuli to each other in various ways.
 * This package implements the core relational frame types that form the building
 * blocks of relational reasoning.</p>
 *
 * <h2>Relational Frame Types</h2>
 *
 * <p>The package provides the following relational frame implementations:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.frames.CoordinationFrame} - Equivalence/sameness relations
 *       ("A is the same as B")</li>
 *   <li>{@link io.almostrealism.frames.ComparativeFrame} - Comparative relations based on
 *       magnitude ("B is larger than A")</li>
 *   <li>{@link io.almostrealism.frames.SpatialFrame} - Spatial/proximity relations
 *       ("A is closer than B")</li>
 *   <li>{@link io.almostrealism.frames.TemporalFrame} - Temporal sequence relations
 *       ("A is before B")</li>
 *   <li>{@link io.almostrealism.frames.CausalFrame} - Cause and effect relations
 *       ("B is because of A")</li>
 *   <li>{@link io.almostrealism.frames.DiecticFrame} - Perspective-dependent relations
 *       ("A is B" from a given viewpoint)</li>
 * </ul>
 *
 * <h2>Core Interface</h2>
 *
 * <p>All frames operate on {@link io.almostrealism.frames.Predicate} instances,
 * which represent the entities, concepts, or stimuli being related.</p>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create predicates representing entities
 * Predicate apple = new MyPredicate("apple");
 * Predicate orange = new MyPredicate("orange");
 *
 * // Create relational frames
 * CoordinationFrame coord = new CoordinationFrame(apple, orange);
 * // "apple is the same as orange" (in the category of fruits)
 *
 * ComparativeFrame comp = new ComparativeFrame(apple, orange);
 * // "orange is larger than apple"
 * }</pre>
 *
 * <h2>Applications</h2>
 *
 * <p>These relational frames support fuzzy reasoning, knowledge representation,
 * and cognitive modeling applications where relationships between concepts need
 * to be explicitly represented and reasoned about.</p>
 *
 * @see io.almostrealism.frames.Predicate
 * @see io.almostrealism.frames.CoordinationFrame
 * @see io.almostrealism.frames.ComparativeFrame
 *
 * @author  Michael Murray
 */
package io.almostrealism.frames;