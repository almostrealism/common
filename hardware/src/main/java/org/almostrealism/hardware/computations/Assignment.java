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
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.code.ScopeInputManager;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.function.Supplier;

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
			for (int i = 0; i < memLength; i++) {
				addVariable(getArgument(0, memLength).ref(i).assign(getArgument(1).getValueRelative(i)));
			}
		}
	}

	@Override
	public int getCount() {
		return getInputs().get(0) instanceof Countable ? ((Countable) getInputs().get(0)).getCount() : 1;
	}

	@Override
	public Scope<Void> getScope() {
		Scope<Void> scope = super.getScope();

		if (!enableRelative) {
			ArrayVariable<Double> output = (ArrayVariable<Double>) getArgument(0, memLength);

			for (int i = 0; i < memLength; i++) {
				Expression index = new KernelIndex();
				if (memLength > 1) index = index.multiply(memLength).add(i);

				TraversableExpression exp = TraversableExpression.traverse(getArgument(1));
				Expression<Double> value = exp == null ? null : exp.getValueAt(index);
				if (value == null) {
					throw new UnsupportedOperationException();
				}

				Variable v;
				TraversableExpression out = TraversableExpression.traverse(output);

				if (out == null) {
					v = output.ref(i).assign(value.getSimplified());
				} else {
					v = new Variable(out.getValueAt(index).getSimpleExpression(getLanguage()),
							false, value.getSimplified(), output.getRootDelegate());
				}

				scope.getVariables().add(v);
			}
		}

		return scope;
	}

	@Override
	public Assignment<T> generate(List<Process<?, ?>> children) {
		if (children.size() != 2) return this;

		Assignment generated = new Assignment<>(memLength, (Supplier) children.get(0), (Supplier) children.get(1));
		generated.setMetadata(getMetadata());
		return generated;
	}
}
