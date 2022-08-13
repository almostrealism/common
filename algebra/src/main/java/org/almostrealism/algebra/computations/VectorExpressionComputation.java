/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.algebra.computations;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducerBase;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class VectorExpressionComputation extends ExpressionComputation<Vector> implements VectorProducerBase {
	public VectorExpressionComputation(List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression, Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(expression, args);
	}

	@Override
	public Vector postProcessOutput(MemoryData output, int offset) {
		return new Vector(output, offset);
	}
}
