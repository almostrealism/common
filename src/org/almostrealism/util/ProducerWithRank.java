/*
 * Copyright 2018 Michael Murray
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

public class ProducerWithRank<T> implements Producer<T> {
	private Producer<T> p;
	private Producer<Scalar> rank;

	/**
	 * This constructor uses this {@link Producer} as the
	 * {@link Producer} argument, which requires that the
	 * {@link #evaluate(Object[])} method be overridden.
	 */
	protected ProducerWithRank(Producer<Scalar> rank) {
		p = this;
	}

	public ProducerWithRank(Producer<T> p, Producer<Scalar> rank) {
		this.p = p;
		this.rank = rank;
	}

	public Producer<T> getProducer() { return p; }

	public Producer<Scalar> getRank() { return rank; }

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
