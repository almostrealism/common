/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.util;

import org.almostrealism.algebra.Scalar;

import java.util.function.Supplier;

public class ProducerWithRankAdapter<T> implements ProducerWithRank<T> {
	private Evaluable<? extends T> p;
	private Evaluable<? extends Scalar> rank;

	/**
	 * This constructor uses this {@link Evaluable} as the
	 * {@link Evaluable} argument, which requires that the
	 * {@link #evaluate(Object[])} method be overridden.
	 */
	protected ProducerWithRankAdapter(Evaluable<Scalar> rank) {
		this.p = this;
		this.rank = rank;
	}

	public ProducerWithRankAdapter(Supplier<Evaluable<? extends T>> p, Supplier<Evaluable<? extends Scalar>> rank) {
		this(p.get(), rank.get());
	}

	public ProducerWithRankAdapter(Evaluable<? extends T> p, Evaluable<? extends Scalar> rank) {
		this.p = p;
		this.rank = rank;
	}

	@Override
	public Evaluable<T> getProducer() { return (Evaluable<T>) p; }

	@Override
	public Evaluable<Scalar> getRank() { return (Evaluable<Scalar>) rank; }

	@Override
	public T evaluate(Object[] args) {
		if (p == this) return null;
		return p.evaluate(args);
	}

	@Override
	public void compact() {
		if (p != this) p.compact();
		rank.compact();
	}
}
