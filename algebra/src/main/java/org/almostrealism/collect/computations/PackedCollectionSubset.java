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

package org.almostrealism.collect.computations;

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.KernelSupport;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class PackedCollectionSubset<T extends PackedCollection<?>>
		extends DynamicCollectionProducerComputationAdapter<PackedCollection<?>, T>
		implements CollectionProducerComputation<T> {
	private int pos[];

	public PackedCollectionSubset(TraversalPolicy shape, Producer<?> collection, int... pos) {
		super(shape, (Supplier) collection);
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Subset cannot be performed without a TraversalPolicy");

		this.pos = pos;
		setShape(shape);
		setDestination(() -> { throw new UnsupportedOperationException(); });
		setInputs(new Destination(), (Supplier) collection);
		init();
	}

	public int getMemLength() { return 1; }

	@Override
	protected MemoryBank<?> createKernelDestination(int len) {
		if (len != getShape().getTotalSize())
			throw new IllegalArgumentException("Subset kernel size must match subset shape (" + getShape().getTotalSize() + ")");

		return new PackedCollection<>(getShape().traverseEach());
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> {
			if (i != 0) throw new IllegalArgumentException("Invalid position");

			Expression index = new Expression(Double.class, KernelSupport.getKernelIndex(0));
			TraversalPolicy inputShape = ((Shape) getInputs().get(1)).getShape();
			Expression<?> p = inputShape.subset(getShape(), index, pos);
			return getArgument(1, inputShape.getTotalSize()).get(p, -1);
		};
	}

	private class Destination implements Producer<PackedCollection<?>>, Delegated<DestinationSupport<T>>, KernelSupport {
		@Override
		public Evaluable<PackedCollection<?>> get() {
			return args -> new PackedCollection<>(getShape().traverseEach());
		}

		@Override
		public DestinationSupport<T> getDelegate() {
			return PackedCollectionSubset.this;
		}
	}
}
