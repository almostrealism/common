/*
 * Copyright 2026 Michael Murray
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
import io.almostrealism.expression.Tangent;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * A computation that applies the hyperbolic tangent element-wise to a {@link PackedCollection}.
 *
 * <p>Chain rule: d/dx[tanh(f(x))] = (1 - tanh(f(x))^2) * f'(x)</p>
 *
 * <p>The derivative is formed at the Producer level, as for {@link CollectionSineComputation} and
 * {@link CollectionCosineComputation}: the cofactor {@code 1 - tanh(f(x))^2} is a computation of its own
 * that multiplies the delta of the input. Differentiating the {@link Tangent} expression instead
 * embeds the argument's expression in the derivative's kernel, which gave a wrong derivative for an
 * argument that is itself a computation over a traversed input, such as the row-wise scaling of a
 * DynamicTanh normalization or the polynomial inside a GELU.</p>
 *
 * @see UnaryCollectionComputation
 * @see io.almostrealism.expression.Tangent
 *
 * @author Michael Murray
 */
public class CollectionHyperbolicTangentComputation extends UnaryCollectionComputation {

	/**
	 * Creates a hyperbolic tangent computation for the given input with the specified output shape.
	 *
	 * @param shape the output shape
	 * @param input the input producer to apply the hyperbolic tangent to
	 */
	public CollectionHyperbolicTangentComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
		super("tanh", shape, x -> Tangent.of(x, true), input);
	}

	/**
	 * Returns the derivative of the hyperbolic tangent at the input, {@code 1 - tanh(input)^2}.
	 *
	 * @return the cofactor of the chain rule
	 */
	@Override
	protected CollectionProducer getCofactor() {
		Producer<PackedCollection> input = getInputs().get(1);
		CollectionProducer tanh = new CollectionHyperbolicTangentComputation(shape(input), input);
		return tanh.multiply(tanh).multiply(-1.0).add(1.0);
	}

	@Override
	protected UnaryCollectionComputation create(TraversalPolicy shape, Producer<PackedCollection> input) {
		return new CollectionHyperbolicTangentComputation(shape, input);
	}
}
