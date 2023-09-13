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

package org.almostrealism.algebra.computations;

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBankProducerBase;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@Deprecated
public class ScalarBankExpressionComputation extends ExpressionComputation<PackedCollection<Scalar>> implements ScalarBankProducerBase {
	public ScalarBankExpressionComputation(List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression, Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(expression, args);
	}

	@Override
	public PackedCollection<Scalar> postProcessOutput(MemoryData output, int offset) {
		return Scalar.scalarBank(((MemoryBank) output).getCount(), output, offset);
	}
}
