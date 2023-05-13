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

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.collect.computations.CollectionProducerComputationAdapter;
import org.almostrealism.hardware.MemoryData;

import java.util.function.Supplier;

@Deprecated
public class ScalarBankSum extends CollectionProducerComputationAdapter<ScalarBank, Scalar> implements ScalarProducerBase {
	private final int count;

	public ScalarBankSum(int count, Supplier<Evaluable<? extends ScalarBank>> input) {
		super(Scalar.shape(), input);
		this.count = count;
	}

	@Override
	public Scope<Scalar> getScope() {
		HybridScope<Scalar> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "ScalarBankSum"));

		Expression<?> i = new StaticReference<>(Integer.class, getVariablePrefix() + "_i");
		String result = getArgument(0, 2).valueAt(0).getExpression();
		String value = getArgument(1, 2 * count).get(i.multiply(2)).getExpression();

		scope.code().accept("for (int " + i + " = 0; " + i + " < " + count +"; " + i + "++) {\n");
		scope.code().accept("    " + result + " = " + result + " + " + value + ";\n");
		scope.code().accept("}\n");
		return scope;
	}

	@Override
	public Scalar postProcessOutput(MemoryData output, int offset) {
		return (Scalar) Scalar.postprocessor().apply(output, offset);
	}
}
