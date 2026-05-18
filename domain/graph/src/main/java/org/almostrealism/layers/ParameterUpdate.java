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

package org.almostrealism.layers;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * Strategy interface for applying a gradient update to a set of learnable weights.
 *
 * <p>Implementations subtract a scaled version of the gradient from the weight tensor.
 * Built-in factory methods cover the most common cases:</p>
 * <ul>
 *   <li>{@link #scaled(double)} — plain gradient descent with a fixed learning rate</li>
 *   <li>{@link #scaled(Producer)} — gradient descent with a dynamic learning rate</li>
 *   <li>{@link #of(Factor)} — gradient descent using a custom gradient transform</li>
 *   <li>{@link #disabled()} — no-op update for frozen weights</li>
 * </ul>
 *
 * @param <T> the concrete {@link PackedCollection} subtype used for weights and gradients
 * @see Learning
 * @see DefaultGradientPropagation
 * @author Michael Murray
 */
// TODO  It may be more consistent for this to be a factory that produces a Cell
// TODO  the Cell::push implementation would accept the gradient and apply it
public interface ParameterUpdate<T extends PackedCollection> {
	/**
	 * Produces an operation that updates {@code weights} using {@code gradient}.
	 *
	 * @param name     a human-readable label used in profiling and operation metadata
	 * @param weights  the weight producer whose backing buffer is to be updated in place
	 * @param gradient the gradient of the loss with respect to {@code weights}
	 * @return a supplier of the update operation
	 */
	Supplier<Runnable> apply(String name, Producer<T> weights, Producer<T> gradient);

	/**
	 * Creates an update that subtracts {@code operator.getResultant(gradient)} from the weights.
	 *
	 * @param operator a factor that transforms the raw gradient before subtraction
	 * @return a new parameter update using the given operator
	 */
	static ParameterUpdate<PackedCollection> of(Factor<PackedCollection> operator) {
		return (name, weights, gradient) ->
				Ops.op(o -> o.a(name + " (\u0394 weights)",
						o.each(weights), o.subtract(o.each(weights), operator.getResultant(gradient))));
	}

	/**
	 * Creates a plain gradient descent update with a fixed learning rate.
	 *
	 * @param learningRate the scalar learning rate applied to each gradient element
	 * @return a new parameter update equivalent to {@code weights -= learningRate * gradient}
	 */
	static ParameterUpdate<PackedCollection> scaled(double learningRate) {
		return scaled(CollectionFeatures.getInstance().cp(PackedCollection.of(learningRate)));
	}

	/**
	 * Creates a gradient descent update whose learning rate is supplied by a producer.
	 *
	 * @param learningRate a producer whose scalar value is multiplied by the gradient
	 * @return a new parameter update equivalent to {@code weights -= learningRate * gradient}
	 */
	static ParameterUpdate<PackedCollection> scaled(Producer<PackedCollection> learningRate) {
		return of(gradient -> Ops.o().multiply(learningRate, gradient));
	}

	/**
	 * Creates a no-op update that leaves weights unchanged.
	 *
	 * <p>Use this for frozen or shared weight tensors that should not be modified during
	 * training.</p>
	 *
	 * @return a parameter update that performs no operation
	 */
	static ParameterUpdate<PackedCollection> disabled() {
		return (name, weights, gradient) -> new OperationList();
	}
}
