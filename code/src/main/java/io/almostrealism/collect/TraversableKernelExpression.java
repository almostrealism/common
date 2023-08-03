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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;

public interface TraversableKernelExpression extends KernelExpression, Shape<TraversableKernelExpression> {
	@Override
	default TraversableKernelExpression reshape(TraversalPolicy shape) {
		if (shape.getTotalSize() != this.getShape().getTotalSize()) {
			throw new IllegalArgumentException("Cannot reshape to a different total size");
		}

		KernelExpression kernel = this;

		return new TraversableKernelExpression() {
			@Override
			public Expression<Double> apply(KernelInput args, PositionExpression pos) {
				return kernel.apply(args, pos);
			}

			@Override
			public TraversalPolicy getShape() { return shape; }
		};
	}
}
