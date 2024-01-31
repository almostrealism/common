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
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

// Use ExpressionComputation or TraversableExpressionComputation instead
@Deprecated
public class ArrayVariableComputation<T extends PackedCollection<?>>
		extends RelativeTraversableProducerComputation<T, T> {
	private List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression;

	@SafeVarargs
	public ArrayVariableComputation(TraversalPolicy shape,
									List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression,
								 	Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		this.expression = expression;
	}

	@Override
	public int getMemLength() { return expression.size(); }

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		int i = index.intValue().orElseThrow(UnsupportedOperationException::new);
		return expression.get(i).apply((List) getArgumentVariables());
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> expression.get(pos).apply((List) getArgumentVariables());
	}
}
