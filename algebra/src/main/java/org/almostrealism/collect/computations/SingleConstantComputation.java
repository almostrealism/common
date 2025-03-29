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

import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.util.NumberFormats;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

public class SingleConstantComputation<T extends PackedCollection<?>> extends CollectionConstantComputation<T> {
	protected final double value;

	public SingleConstantComputation(TraversalPolicy shape, double value) {
		this("constant(" + NumberFormats.formatNumber(value) + ")", shape, value);
	}

	protected SingleConstantComputation(String name, TraversalPolicy shape, double value) {
		super(name, shape);
		this.value = value;
	}

	@Override
	protected ConstantCollectionExpression getExpression(TraversableExpression... args) {
		return new ConstantCollectionExpression(getShape(), e(value));
	}

	public double getConstantValue() { return value; }

	@Override
	public Evaluable<T> getShortCircuit() {
		return args -> {
			PackedCollection v = new PackedCollection(getShape());
			v.fill(value);
			return getPostprocessor() == null ? (T) v : getPostprocessor().apply(v, 0);
		};
	}

	@Override
	public boolean isIdentity(int width) {
		return value == 1.0 && width == 1 && getShape().getTotalSizeLong() == 1;
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return this;
	}

	@Override
	public CollectionProducer<T> traverse(int axis) {
		return new SingleConstantComputation<>(getShape().traverse(axis), value);
	}

	@Override
	public CollectionProducerComputation<T> reshape(TraversalPolicy shape) {
		return new SingleConstantComputation<>(shape, value);
	}

	@Override
	public String description() { return NumberFormats.formatNumber(value); }
}
