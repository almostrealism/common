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

package org.almostrealism.optimize;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;

public class MeanAbsoluteError implements LossProvider, CodeFeatures {
	private TraversalPolicy outputShape;
	private Evaluable<PackedCollection<?>> loss;

	public MeanAbsoluteError(TraversalPolicy outputShape) {
		this.outputShape = outputShape;
		this.loss = cv(outputShape, 0).subtract(cv(outputShape, 1)).abs().get();
	}

	@Override
	public double loss(PackedCollection<?> output, PackedCollection<?> target) {
		return loss.evaluate(output, target).doubleStream().average().orElse(0);
	}

	@Override
	public Producer<PackedCollection<?>> gradient(Producer<PackedCollection<?>> output,
												  Producer<PackedCollection<?>> target) {
		double f = 1.0 / outputShape.getTotalSize();
		return c(output).greaterThan(c(target), c(f), c(-f));
	}
}
