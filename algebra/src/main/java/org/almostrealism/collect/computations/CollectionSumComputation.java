/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

public class CollectionSumComputation<T extends PackedCollection<?>> extends AggregatedProducerComputation<T> {
	public CollectionSumComputation(Producer<PackedCollection<?>> input) {
		this(CollectionFeatures.getInstance().shape(input), input);
	}

	protected CollectionSumComputation(TraversalPolicy shape, Producer<PackedCollection<?>> input) {
		super("sum", shape.replace(new TraversalPolicy(1)), shape.getSize(),
				(args, index) -> new DoubleConstant(0.0),
				(out, arg) -> out.add(arg),
				input);
		setReplaceLoop(true);
	}

	@Override
	public CollectionSumComputation<T> generate(List<Process<?, ?>> children) {
		return new CollectionSumComputation((Producer) children.get(1));
	}

	@Override
	protected boolean isSignatureSupported() { return true; }
}
