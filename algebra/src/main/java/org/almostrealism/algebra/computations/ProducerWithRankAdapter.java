/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerWithRank;
import org.almostrealism.collect.PackedCollection;

import java.util.stream.Stream;

/**
 * An adapter that associates a {@link Producer} with a rank value for sorting and selection operations.
 *
 * <p>
 * {@link ProducerWithRankAdapter} wraps a producer and pairs it with a scalar rank value, enabling:
 * <ul>
 *   <li>Ranking multiple producers by confidence/priority</li>
 *   <li>Selecting the highest-ranked option</li>
 *   <li>Sorting operations based on rank</li>
 * </ul>
 *
 * <p>
 * The rank is typically a scalar value representing:
 * <ul>
 *   <li>Confidence score (0.0 to 1.0)</li>
 *   <li>Distance metric (lower = better)</li>
 *   <li>Priority value</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Create producer with confidence rank
 * Producer<Vector> prediction = ...;
 * Producer<PackedCollection<?>> confidence = c(0.95);
 *
 * ProducerWithRankAdapter<Vector> rankedPrediction =
 *     new ProducerWithRankAdapter<>(prediction, confidence);
 *
 * // Access the producer and its rank
 * Producer<Vector> p = rankedPrediction.getProducer();
 * Producer<PackedCollection<?>> rank = rankedPrediction.getRank();
 * }</pre>
 *
 * @param <T>  the type produced by the wrapped producer
 * @author  Michael Murray
 * @see HighestRank
 * @see ProducerWithRank
 */
public class ProducerWithRankAdapter<T> implements ProducerWithRank<T, PackedCollection<?>>, ScopeLifecycle, Shape<T> {
	private Producer<T> p;
	private Producer<PackedCollection<?>> rank;

	/**
	 * Protected constructor for subclasses that implement their own producer logic.
	 * When using this constructor, the subclass must override {@link #get()}.
	 *
	 * @param rank  the rank producer
	 */
	protected ProducerWithRankAdapter(Producer rank) {
		this.p = this;
		this.rank = rank;
	}

	/**
	 * Creates a new producer-with-rank adapter.
	 *
	 * @param p  the producer to wrap
	 * @param rank  the rank producer
	 */
	public ProducerWithRankAdapter(Producer<T> p, Producer rank) {
		this.p = p;
		this.rank = rank;
	}

	/**
	 * Returns the wrapped producer.
	 *
	 * @return the producer
	 */
	@Override
	public Producer<T> getProducer() { return p; }

	/**
	 * Returns the rank producer.
	 *
	 * @return the rank value producer
	 */
	@Override
	public Producer<PackedCollection<?>> getRank() { return rank; }

	/**
	 * Prepares arguments for both the producer and rank.
	 *
	 * @param map  the argument map
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.prepareArguments(Stream.of(getProducer()), map);
		ScopeLifecycle.prepareArguments(Stream.of(getRank()), map);
	}

	/**
	 * Prepares scope for both the producer and rank.
	 *
	 * @param manager  the scope input manager
	 * @param context  the kernel structure context
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		ScopeLifecycle.prepareScope(Stream.of(getProducer()), manager, context);
		ScopeLifecycle.prepareScope(Stream.of(getRank()), manager, context);
	}

	/**
	 * Resets arguments for both the producer and rank.
	 */
	@Override
	public void resetArguments() {
		ScopeLifecycle.resetArguments(Stream.of(getProducer()));
		ScopeLifecycle.resetArguments(Stream.of(getRank()));
	}

	/**
	 * Returns the evaluable from the wrapped producer.
	 *
	 * @return the evaluable
	 * @throws UnsupportedOperationException if using the protected constructor and get() is not overridden
	 */
	@Override
	public Evaluable<T> get() {
		if (getProducer() == this) {
			throw new UnsupportedOperationException();
		} else {
			return getProducer() == null ? null : p.get();
		}
	}

	/**
	 * Returns the shape of the wrapped producer.
	 *
	 * @return the traversal policy
	 * @throws UnsupportedOperationException if the wrapped producer doesn't implement Shape
	 */
	@Override
	public TraversalPolicy getShape() {
		if (getProducer() instanceof Shape) {
			return ((Shape<?>) getProducer()).getShape();
		}
		throw new UnsupportedOperationException("Wrapped producer does not implement Shape");
	}

	/**
	 * Reshapes the wrapped producer.
	 *
	 * @param shape  the new shape
	 * @return the reshaped producer
	 * @throws UnsupportedOperationException if the wrapped producer doesn't implement Shape
	 */
	@Override
	public T reshape(TraversalPolicy shape) {
		if (getProducer() instanceof Shape) {
			return (T) ((Shape<?>) getProducer()).reshape(shape);
		}
		throw new UnsupportedOperationException("Wrapped producer does not implement Shape");
	}

	/**
	 * Traverses the wrapped producer along the specified axis.
	 *
	 * @param axis  the axis to traverse
	 * @return the traversed producer
	 * @throws UnsupportedOperationException if the wrapped producer doesn't implement Shape
	 */
	@Override
	public T traverse(int axis) {
		if (getProducer() instanceof Shape) {
			return (T) ((Shape<?>) getProducer()).traverse(axis);
		}
		throw new UnsupportedOperationException("Wrapped producer does not implement Shape");
	}
}
