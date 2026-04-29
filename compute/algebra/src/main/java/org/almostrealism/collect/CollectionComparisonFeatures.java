/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect;

/**
 * Mixin interface providing element-wise comparison operations on
 * {@link CollectionProducer} instances. Extends {@link CollectionFeatures}
 * so all comparison, {@code compute}, {@code c}, and {@code shape} helpers
 * are available via the inherited {@link ComparisonFeatures} contract.
 *
 * <p>{@link org.almostrealism.algebra.AlgebraFeatures} extends this interface
 * so comparison methods are reachable from any {@link CollectionProducer}.</p>
 *
 * @author  Michael Murray
 * @see CollectionFeatures
 * @see ComparisonFeatures
 * @see CollectionProducer
 */
public interface CollectionComparisonFeatures extends CollectionFeatures {
}
