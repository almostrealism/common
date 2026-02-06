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
import io.almostrealism.expression.Cosine;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that performs element-wise cosine function on {@link PackedCollection}s.
 *
 * <p>Chain rule: d/dx[cos(f(x))] = -sin(f(x)) * f'(x)</p>
 *
 * @see UnaryCollectionComputation
 * @see CollectionSineComputation
 * @see io.almostrealism.expression.Cosine
 *
 * @author Michael Murray
 */
public class CollectionCosineComputation extends UnaryCollectionComputation {

	public CollectionCosineComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
		super("cos", shape, Cosine::of, input);
	}

	@Override
	protected CollectionProducer getCofactor(CollectionProducer input) {
		// -sin(f)
		return new CollectionSineComputation(shape(input), input).multiply(-1.0);
	}

	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new CollectionCosineComputation(getShape(),
				(Producer<PackedCollection>) children.get(1))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}
}
