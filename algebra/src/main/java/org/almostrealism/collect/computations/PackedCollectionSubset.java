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
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.KernelSupport;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.Supplier;
import java.util.stream.IntStream;

public class PackedCollectionSubset<T extends PackedCollection<?>>
		extends KernelProducerComputationAdapter<PackedCollection<?>, T> {
	private Expression pos[];

	public PackedCollectionSubset(TraversalPolicy shape, Producer<?> collection, int... pos) {
		this(shape, collection, IntStream.of(pos).mapToObj(i -> new IntegerConstant(i)).toArray(Expression[]::new));
	}

	public PackedCollectionSubset(TraversalPolicy shape, Producer<?> collection, Expression... pos) {
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


	// TODO  This custom destination creation should not be necessary
	@Override
	protected MemoryBank<?> createKernelDestination(int len) {
		if (len != getShape().getTotalSize())
			throw new IllegalArgumentException("Subset kernel size must match subset shape (" + getShape().getTotalSize() + ")");

		return new PackedCollection<>(getShape().traverseEach());
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		TraversalPolicy inputShape = ((Shape) getInputs().get(1)).getShape();
		Expression<?> p = inputShape.subset(getShape(), index, pos);

		// return getArgument(1, inputShape.getTotalSize()).getRaw(p);
		return getCollectionArgumentVariable(1).getValueAt(p);
	}

	private class Destination implements Producer<PackedCollection<?>>, Delegated<DestinationSupport<T>>, Countable, KernelSupport {
		@Override
		public Evaluable<PackedCollection<?>> get() {
			return args -> new PackedCollection<>(getShape().traverseEach());
		}

		@Override
		public DestinationSupport<T> getDelegate() {
			return PackedCollectionSubset.this;
		}

		@Override
		public int getCount() { return getShape().getTotalSize(); }
	}
}
