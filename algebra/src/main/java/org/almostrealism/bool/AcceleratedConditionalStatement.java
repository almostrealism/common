/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.bool;

import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.OutputSupport;
import io.almostrealism.scope.Variable;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Compactable;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public interface AcceleratedConditionalStatement<T extends MemoryData> extends Producer<T>, OutputSupport, Compactable {
	Expression getCondition();

	List<Supplier<Evaluable<? extends MemoryData>>> getInputs();
	List<Argument<? extends MemoryData>> getArguments();
	List<Variable<?, ?>> getVariables();

	List<ArrayVariable<Scalar>> getOperands();

	IntFunction<Expression<Double>> getTrueValueExpression();
	IntFunction<Expression<Double>> getFalseValueExpression();
}
