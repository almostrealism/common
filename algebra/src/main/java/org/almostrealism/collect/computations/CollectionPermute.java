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

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;

import java.util.List;

public class CollectionPermute<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	private int order[];

	public CollectionPermute(Producer<?> collection, int... order) {
		super("permute", computeShape(collection, order), null, collection);
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Permute cannot be performed without a TraversalPolicy");

		this.order = order;
	}

	@Override
	public int getMemLength() { return 1; }

	@Override
	public long getCountLong() {
		return getShape().traverseEach().getCountLong();
	}

	@Override
	protected int getStatementCount(KernelStructureContext context) {
		return getMemLength();
	}

	@Override
	protected Expression projectIndex(Expression index) {
		TraversalPolicy inputShape = ((Shape) getInputs().get(1)).getShape();
		TraversalPolicy outputShape = inputShape.permute(order);

		Expression actualPosition[] = getShape().position(index);
		return outputShape.index(actualPosition);
	}

	@Override
	public CollectionPermute<T> generate(List<Process<?, ?>> children) {
		if (getChildren().size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new CollectionPermute<>((Producer<?>) children.get(1), order);
	}

	protected static TraversalPolicy computeShape(Producer<?> collection, int... order) {
		if (!(collection instanceof Shape)) {
			throw new IllegalArgumentException("Collection must implement Shape to compute permute shape");
		}

		return ((Shape) collection).getShape().permute(order).extentShape();
	}
}
