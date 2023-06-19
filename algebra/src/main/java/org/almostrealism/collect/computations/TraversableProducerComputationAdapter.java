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
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;

import java.util.OptionalInt;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class TraversableProducerComputationAdapter<I extends PackedCollection<?>, O extends PackedCollection<?>>
		extends TraversableProducerComputationBase<I, O> {

	protected TraversableProducerComputationAdapter() { }

	public TraversableProducerComputationAdapter(TraversalPolicy outputShape, Supplier<Evaluable<? extends I>>... arguments) {
		super(outputShape, arguments);
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		OptionalInt i = index.intValue();

		if (i.isPresent()) {
			return getValueFunction().apply(i.getAsInt());
		} else {
			return null;
		}
	}

	public abstract IntFunction<Expression<Double>> getValueFunction();
}
