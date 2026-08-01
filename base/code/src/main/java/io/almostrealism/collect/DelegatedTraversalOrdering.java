/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Delegated;

/**
 * A {@link TraversalOrdering} that composes two orderings by applying one after the other.
 *
 * <p>When {@link #indexOf(Expression)} is called, the inner {@code order} is applied first
 * to remap the input index, and then the result is passed to the {@code delegate} ordering.
 * This allows traversal orderings to be chained with {@link TraversalOrdering#compose}.</p>
 */
public class DelegatedTraversalOrdering implements TraversalOrdering, Delegated<TraversalOrdering> {
	/** The first ordering to apply. */
	private TraversalOrdering order;

	/** The second ordering to apply after the first. */
	private TraversalOrdering delegate;

	/**
	 * Creates a composed ordering that applies {@code order} and then {@code delegate}.
	 *
	 * @param order    the first ordering
	 * @param delegate the second ordering applied to the result of the first
	 */
	public DelegatedTraversalOrdering(TraversalOrdering order, TraversalOrdering delegate) {
		this.order = order;
		this.delegate = delegate;
	}

	/**
	 * Returns the first (inner) ordering applied by this composition.
	 *
	 * @return the first traversal ordering
	 */
	public TraversalOrdering getOrder() {
		return order;
	}

	/** {@inheritDoc} Returns the second (outer) ordering applied by this composition. */
	@Override
	public TraversalOrdering getDelegate() {
		return delegate;
	}

	/** {@inheritDoc} Returns {@code delegate.indexOf(order.indexOf(idx))}. */
	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		return getDelegate().indexOf(order.indexOf(idx));
	}
}
