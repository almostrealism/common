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

public class DelegatedTraversalOrdering implements TraversalOrdering, Delegated<TraversalOrdering> {
	private TraversalOrdering order;
	private TraversalOrdering delegate;

	public DelegatedTraversalOrdering(TraversalOrdering order, TraversalOrdering delegate) {
		this.order = order;
		this.delegate = delegate;
	}

	public TraversalOrdering getOrder() {
		return order;
	}

	@Override
	public TraversalOrdering getDelegate() {
		return delegate;
	}

	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		return getDelegate().indexOf(order.indexOf(idx));
	}
}
