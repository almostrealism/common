/*
 * Copyright 2021 Michael Murray
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
import org.almostrealism.algebra.Scalar;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerWithRank;

import java.util.stream.Stream;

public class ProducerWithRankAdapter<T> implements ProducerWithRank<T, Scalar>, ScopeLifecycle {
	private Producer<T> p;
	private Producer<Scalar> rank;

	/**
	 * This constructor uses this {@link Producer} as the
	 * {@link Producer} argument, which requires that the
	 * {@link #get()} method be overridden.
	 */
	protected ProducerWithRankAdapter(Producer<Scalar> rank) {
		this.p = this;
		this.rank = rank;
	}

	public ProducerWithRankAdapter(Producer<T> p, Producer<Scalar> rank) {
		this.p = p;
		this.rank = rank;
	}

	@Override
	public Producer<T> getProducer() { return p; }

	@Override
	public Producer<Scalar> getRank() { return rank; }

	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.prepareArguments(Stream.of(getProducer()), map);
		ScopeLifecycle.prepareArguments(Stream.of(getRank()), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		ScopeLifecycle.prepareScope(Stream.of(getProducer()), manager);
		ScopeLifecycle.prepareScope(Stream.of(getRank()), manager);
	}

	@Override
	public void resetArguments() {
		ScopeLifecycle.resetArguments(Stream.of(getProducer()));
		ScopeLifecycle.resetArguments(Stream.of(getRank()));
	}

	@Override
	public void compact() {
		getProducer().compact();
		getRank().compact();
	}

	@Override
	public Evaluable<T> get() { return p == null ? null : p.get(); }
}
