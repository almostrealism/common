/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.collect;

import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;

/**
 * Base interface for producers that generate shaped collections. This interface
 * combines {@link Producer} semantics with {@link Shape} and {@link Countable}
 * capabilities, providing a foundation for collection-producing operations.
 *
 * <p>{@code CollectionProducerBase} serves as the basis for producers that:</p>
 * <ul>
 *   <li>Generate collections with known shapes</li>
 *   <li>Can report their element count (fixed or variable)</li>
 *   <li>Support shape transformations that return producer instances</li>
 * </ul>
 *
 * <p>The count information is derived from the shape's count, allowing
 * consistent handling of collection sizes across the producer hierarchy.</p>
 *
 * @param <T> the type of collection produced
 * @param <P> the producer type returned by shape transformations (typically self-referential)
 *
 * @see Producer
 * @see Shape
 * @see Countable
 */
public interface CollectionProducerBase<T, P extends Producer<T>> extends Producer<T>, Shape<P>, Countable {

	/**
	 * Returns the number of elements in the produced collection.
	 * This value is derived from the shape's count.
	 *
	 * @return the element count as a long value
	 */
	@Override
	default long getCountLong() { return getShape().getCountLong(); }

	/**
	 * Returns a human-readable description of this producer including
	 * class name, count, fixed/variable status, and shape information.
	 *
	 * @return a descriptive string for debugging and logging
	 */
	@Override
	default String describe() {
		return getClass().getSimpleName() + " " +
				getCountLong() + "x" +
				(isFixedCount() ? " (fixed) " : " (variable) ") +
				getShape().toString();
	}

	/**
	 * Indicates whether this producer is directly computable as a collection computation,
	 * meaning it can participate in kernel-level evaluation without additional wrapping.
	 * Algebraic simplifications that replace one operand with another are only applied
	 * to directly computable operands.
	 *
	 * @return true if this producer is directly computable, false otherwise
	 */
	default boolean isDirectlyComputable() {
		return false;
	}

	/**
	 * Indicates whether an arbitrary producer is directly computable, as
	 * {@link #isDirectlyComputable()} does for a {@link CollectionProducerBase}.
	 * A producer that is not a {@link CollectionProducerBase} is not directly computable.
	 *
	 * @param producer the producer to inspect
	 * @return true if the producer is directly computable, false otherwise
	 */
	static boolean isDirectlyComputable(Producer<?> producer) {
		return producer instanceof CollectionProducerBase &&
				((CollectionProducerBase<?, ?>) producer).isDirectlyComputable();
	}
}
