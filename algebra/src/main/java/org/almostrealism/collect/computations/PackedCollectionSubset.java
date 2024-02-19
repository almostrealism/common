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
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryBank;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class PackedCollectionSubset<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	private Expression pos[];

	public PackedCollectionSubset(TraversalPolicy shape, Producer<?> collection, int... pos) {
		this(shape, collection, IntStream.of(pos).mapToObj(i -> new IntegerConstant(i)).toArray(Expression[]::new));
	}

	public PackedCollectionSubset(TraversalPolicy shape, Producer<?> collection, Expression... pos) {
		super(shape, null, collection);
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Subset cannot be performed without a TraversalPolicy");

		this.pos = pos;
		setShape(shape);
		setInputs(new Destination(), (Supplier) collection);
		init();
	}

	public PackedCollectionSubset(TraversalPolicy shape, Producer<?> collection, Producer<?> pos) {
		super(shape, null, collection);
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Subset cannot be performed without a TraversalPolicy");

		if (!shape(pos).equalsIgnoreAxis(shape(shape.getDimensions()))) {
			throw new IllegalArgumentException();
		}

		setShape(shape);
		setInputs(new Destination(), (Supplier) collection, (Supplier) pos);
		init();
	}

	public int getMemLength() { return 1; }

	@Override
	public int getCount() {
		return getShape().traverseEach().getCount();
	}

	// TODO  This custom destination creation should not be necessary
	@Override
	protected MemoryBank<?> createDestination(int len) {
		if (len != getShape().getTotalSize())
			throw new IllegalArgumentException("Subset kernel size must match subset shape (" + getShape().getTotalSize() + ")");

		return new PackedCollection<>(getShape().traverseEach());
	}

	@Override
	protected Expression projectIndex(Expression index) {
		TraversalPolicy inputShape = ((Shape) getInputs().get(1)).getShape();

		Expression<?> p;

		if (pos == null) {
			Expression pos[] = new Expression[inputShape.getDimensions()];
			for (int i = 0; i < pos.length; i++)
				pos[i] = getCollectionArgumentVariable(2).getValueAt(e(i)).toInt();

			p = inputShape.subset(getShape(), index, pos);
		} else {
			p = inputShape.subset(getShape(), index, pos);
		}

		return p;
	}

	@Override
	public PackedCollectionSubset<T> generate(List<Process<?, ?>> children) {
		if (getChildren().size() == 3) {
			return new PackedCollectionSubset<>(getShape(), (Producer<?>) children.get(1), (Producer<?>) children.get(2));
		} else if (getChildren().size() == 2) {
			return new PackedCollectionSubset<>(getShape(), (Producer<?>) children.get(1), pos);
		} else {
			throw new UnsupportedOperationException();
		}
	}

	private class Destination implements Producer<PackedCollection<?>>, Delegated<Countable>, Countable {
		@Override
		public Evaluable<PackedCollection<?>> get() {
			return args -> new PackedCollection<>(getShape().traverseEach());
		}

		@Override
		public Countable getDelegate() {
			return PackedCollectionSubset.this;
		}

		@Override
		public int getCount() { return getShape().getTotalSize(); }
	}
}
