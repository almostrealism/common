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

package io.almostrealism.relation;

/**
 * A {@link Producer} paired with a ranking value for prioritized selection.
 *
 * <p>{@link ProducerWithRank} wraps a producer along with a ranking metric.
 * This is useful for scenarios where multiple producers need to be compared
 * or prioritized based on some criterion.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Selecting the best producer from a set of candidates</li>
 *   <li>Implementing top-k selection in beam search</li>
 *   <li>Prioritizing computations based on cost or quality metrics</li>
 *   <li>Sorting producers by relevance or efficiency</li>
 * </ul>
 *
 * <h2>Delegation Pattern</h2>
 * <p>This interface delegates evaluation to an underlying producer through
 * {@link #getProducer()}, while providing access to the ranking value
 * through {@link #getRank()}.</p>
 *
 * @param <T> the type of the computation result
 * @param <R> the type of the ranking value
 *
 * @see Producer
 *
 * @author Michael Murray
 */
public interface ProducerWithRank<T, R> extends Producer<T> {
	/**
	 * Returns an {@link Evaluable} by delegating to the underlying producer.
	 *
	 * @return the evaluable from the underlying producer
	 */
	@Override
	default Evaluable<T> get() {
		return getProducer().get();
	}

	/**
	 * Returns the underlying producer that performs the computation.
	 *
	 * @return the producer
	 */
	Producer<T> getProducer();

	/**
	 * Returns a producer that computes the ranking value.
	 *
	 * <p>The ranking value can be used to compare this producer against
	 * others or to make selection decisions.</p>
	 *
	 * @return a producer for the ranking value
	 */
	Producer<R> getRank();
}
