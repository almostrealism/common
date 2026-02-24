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

// TODO  It may be more consistent for this to be a factory that produces a Cell
// TODO  the Cell::push implementation would accept the gradient and apply it
public interface ParameterUpdate<T extends PackedCollection> {
	Supplier<Runnable> apply(String name, Producer<T> weights, Producer<T> gradient);

	static ParameterUpdate<PackedCollection> of(Factor<PackedCollection> operator) {
		return (name, weights, gradient) ->
				Ops.op(o -> o.a(name + " (\u0394 weights)",
						o.each(weights), o.subtract(o.each(weights), operator.getResultant(gradient))));
	}

	static ParameterUpdate<PackedCollection> scaled(double learningRate) {
		return scaled(CollectionFeatures.getInstance().cp(PackedCollection.of(learningRate)));
	}

	static ParameterUpdate<PackedCollection> scaled(Producer<PackedCollection> learningRate) {
		return of(gradient -> Ops.o().multiply(learningRate, gradient));
	}

	static ParameterUpdate<PackedCollection> disabled() {
		return (name, weights, gradient) -> new OperationList();
	}
}
