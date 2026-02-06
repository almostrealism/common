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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Sine;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that performs element-wise sine function on {@link PackedCollection}s.
 *
 * <p>Chain rule: d/dx[sin(f(x))] = cos(f(x)) * f'(x)</p>
 *
 * @see UnaryCollectionComputation
 * @see CollectionCosineComputation
 * @see io.almostrealism.expression.Sine
 *
 * @author Michael Murray
 */
public class CollectionSineComputation extends UnaryCollectionComputation {

	public CollectionSineComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
		super("sin", shape, Sine::of, input);
	}

	@Override
	protected CollectionProducer getCofactor(CollectionProducer input) {
		return new CollectionCosineComputation(shape(input), input);
	}

	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new CollectionSineComputation(getShape(),
				(Producer<PackedCollection>) children.get(1))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}
}
