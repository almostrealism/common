/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.expression;

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

import java.util.function.IntFunction;

/**
 * Implementors of MultiExpression should migrate to implementing TraversableExpression
 * while the {@link MultiExpression#getAssignmentFunction(Variable)} need not be part of
 * the interface, and should eventually be part of {@link io.almostrealism.code.ProducerComputationBase}.
 *
 * The problem with this interface is that because it does not allow for retrieving values
 * using an {@link Expression} for the index, it is not possible to embed computations
 * based on {@link MultiExpression} into other computations that retrieve values without
 * necessarily knowing, ahead of kernel evaluation time, their index. A potential workaround
 * for this is for implementors to (1) reliably provide only a single expression for index 0,
 * (2) for that expression to contain the necessary use of the kernel index for kernel evaluation,
 * and (3) for the computation that embeds it to always use the same kernel size as the one
 * assumed by the embedded computation. However, this is such an extreme constraint, that
 * it is safe to assume it doesn't make any sense to even attempt it.
 */
@Deprecated
public interface MultiExpression<T> {
	boolean enableExpressionSimplification = true;

	default IntFunction<Variable<T, ?>> getAssignmentFunction(Variable<?, ?> outputVariable) {
		if (enableExpressionSimplification) {
			return i ->
					new Variable(((ArrayVariable) outputVariable).valueAt(i).simplify().getExpression(),
							false, getValue(i), outputVariable.getRootDelegate());
		} else {
			return i ->
					new Variable(((ArrayVariable) outputVariable).valueAt(i).getExpression(),
							false, getValue(i), outputVariable.getRootDelegate());
		}
	}

	Expression<T> getValue(int pos);
}
