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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.ArithmeticSequenceExpression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.IntStream;

public class ArithmeticSequenceComputation<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {
	private boolean fixedCount;
	private double initial, rate;

	public ArithmeticSequenceComputation(double initial) {
		this(new TraversalPolicy(1), false, initial);
	}

	public ArithmeticSequenceComputation(TraversalPolicy shape, double initial) {
		this(shape, true, initial, 1);
	}

	public ArithmeticSequenceComputation(TraversalPolicy shape, boolean fixedCount, double initial) {
		this(shape, fixedCount, initial, 1);
	}

	public ArithmeticSequenceComputation(TraversalPolicy shape, boolean fixedCount, double initial, double rate) {
		super("linearSeq", shape);
		this.fixedCount = fixedCount;
		this.initial = initial;
		this.rate = rate;
	}

	@Override
	public ArithmeticSequenceComputation<T> multiply(double factor) {
		return new ArithmeticSequenceComputation<>(getShape(), fixedCount, initial * factor, rate * factor);
	}

	@Override
	public boolean isFixedCount() { return fixedCount; }

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new ArithmeticSequenceExpression(getShape(), initial, 1);
	}

	public Evaluable<T> get() {
		return args -> {
			warn("Direct evaluation of arithmetic sequence");
			return (T) pack(IntStream.range(0, getShape().getTotalSize())
					.mapToDouble(i -> initial + i * rate).toArray());
		};
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return this;
	}
}
