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
	private Producer<? extends T> p;
	private Producer<? extends Scalar> rank;

	/**
	 * This constructor uses this {@link Producer} as the
	 * {@link Producer} argument, which requires that the
	 * {@link #evaluate(Object[])} method be overridden.
	 */
	protected ProducerWithRankAdapter(Producer<Scalar> rank) {
		this.p = this;
		this.rank = rank;
	}

	public ProducerWithRankAdapter(Supplier<Producer<? extends T>> p, Supplier<Producer<? extends Scalar>> rank) {
		this(p.get(), rank.get());
	}

	public ProducerWithRankAdapter(Producer<? extends T> p, Producer<? extends Scalar> rank) {
		this.p = p;
		this.rank = rank;
	}

	@Override
	public Producer<T> getProducer() { return (Producer<T>) p; }

	@Override
	public Producer<Scalar> getRank() { return (Producer<Scalar>) rank; }

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
