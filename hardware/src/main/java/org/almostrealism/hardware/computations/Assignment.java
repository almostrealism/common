/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.code.ScopeInputManager;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.hardware.MemoryData;

import java.util.function.Supplier;
import java.util.stream.IntStream;

public class Assignment<T extends MemoryData> extends OperationComputationAdapter<T> {
	public static boolean enableRelative = !Hardware.enableKernelOps;

	private final int memLength;

	public Assignment(int memLength, Supplier<Evaluable<? extends T>> result, Supplier<Evaluable<? extends T>> value) {
		super(result, value);
		this.memLength = memLength;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		purgeVariables();

		if (enableRelative) {
			IntStream.range(0, memLength).mapToObj(i ->
					new Variable(getArgument(0, memLength).valueAt(i).getSimpleExpression(), false,
							getArgument(1).getValueRelative(i), getArgument(0, memLength))).forEach(this::addVariable);
		}
	}

	@Override
	public Scope<Void> getScope() {
		Scope<Void> scope = super.getScope();

		if (!enableRelative) {
			ArrayVariable<Double> output = (ArrayVariable<Double>) getArgument(0, memLength);

			for (int i = 0; i < memLength; i++) {
				Expression index = new KernelIndex(0);
				if (memLength > 1) index = index.multiply(memLength).add(i);

				TraversableExpression exp = TraversableExpression.traverse(getArgument(1));
				Expression<Double> value = exp == null ? null : exp.getValueAt(index);
				if (value == null)
					throw new UnsupportedOperationException();

				Variable v = new Variable(output.valueAt(i).getSimpleExpression(),
						false, value.getSimplified(), output.getRootDelegate());
				scope.getVariables().add(v);
			}
		}

		return scope;
	}
}
