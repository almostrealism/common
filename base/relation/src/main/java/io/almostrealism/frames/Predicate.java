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

package io.almostrealism.frames;

/**
 * A marker interface representing a predicate in Relational Frame Theory (RFT).
 *
 * <p>In RFT, predicates are the basic elements that can be related through
 * relational frames. A predicate can represent any entity, concept, event,
 * or symbol that participates in a relational network. Predicates form the
 * nodes in a relational frame, while the frame classes (such as
 * {@link SpatialFrame}, {@link TemporalFrame}, etc.) define the relationships
 * between them.</p>
 *
 * <p>This interface is intentionally minimal, serving as a type marker that
 * allows diverse implementations while ensuring type safety when constructing
 * relational frames. Implementations may represent:</p>
 * <ul>
 *   <li>Physical objects or entities</li>
 *   <li>Abstract concepts or categories</li>
 *   <li>Events or actions</li>
 *   <li>Symbols or linguistic elements</li>
 *   <li>Computed or derived values</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Predicate apple = new FruitPredicate("apple");
 * Predicate orange = new FruitPredicate("orange");
 * ComparativeFrame comparison = new ComparativeFrame(apple, orange);
 * // Represents: "orange is larger than apple"
 * }</pre>
 *
 * @see SpatialFrame
 * @see TemporalFrame
 * @see CausalFrame
 * @see ComparativeFrame
 * @see CoordinationFrame
 * @see DiecticFrame
 *
 * @author  Michael Murray
 */
public interface Predicate {

}
