/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.optimize;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;

public class MeanSquaredError implements LossProvider, CodeFeatures {
	private TraversalPolicy outputShape;
	private Evaluable<PackedCollection<?>> loss;

	public MeanSquaredError(TraversalPolicy outputShape) {
		this.outputShape = outputShape;
		this.loss = cv(outputShape, 0).subtract(cv(outputShape, 1)).pow(2.0).get();
	}

	@Override
	public double loss(PackedCollection<?> output, PackedCollection<?> target) {
		return loss.evaluate(output, target).doubleStream().sum();
	}

	@Override
	public Producer<PackedCollection<?>> gradient(Producer<PackedCollection<?>> output, Producer<PackedCollection<?>> target) {
		return c(2).multiply(cv(outputShape, 0).subtract(cv(outputShape, 1)));
	}
}
