/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Countable;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;

public class Assignment<T extends MemoryData> extends OperationComputationAdapter<T> {
	public static boolean enableAdaptiveMemLength = true;

	private final int memLength;

	public Assignment(int memLength, Supplier<Evaluable<? extends T>> result, Supplier<Evaluable<? extends T>> value) {
		super(result, value);
		this.memLength = memLength;

		if (memLength > ScopeSettings.maxStatements) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		metadata = super.prepareMetadata(metadata);

		if (getInputs().get(0) instanceof Shape<?>) {
			metadata = metadata.withShape(((Shape<?>) getInputs().get(0)).getShape());
		}

		return metadata;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		purgeVariables();
	}

	@Override
	public long getCountLong() {
		return getInputs().get(0) instanceof Countable ? ((Countable) getInputs().get(0)).getCountLong() : 1;
	}

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		Scope<Void> scope = super.getScope(context);

		int len = memLength;
		OptionalLong contextCount = context.getKernelMaximum();

		if (contextCount.isPresent() && contextCount.getAsLong() != getCountLong()) {
			if (enableAdaptiveMemLength && getCountLong() % contextCount.getAsLong() == 0) {
				len = Math.toIntExact(getCountLong() / contextCount.getAsLong());
			} else {
				throw new UnsupportedOperationException();
			}
		}

		ArrayVariable<Double> output = (ArrayVariable<Double>) getArgument(0, len);

		for (int i = 0; i < len; i++) {
			Expression index = new KernelIndex(context);
			if (len > 1) index = index.multiply(len).add(i);

			TraversableExpression exp = TraversableExpression.traverse(getArgument(1));
			Expression<Double> value = exp == null ? getArgument(1).valueAt(index) : exp.getValueAt(index);
			if (value == null) {
				throw new UnsupportedOperationException();
			}

			ExpressionAssignment<?> v;
			TraversableExpression out = TraversableExpression.traverse(output);

			if (out == null) {
				v = output.referenceRelative(i).assign(value);
			} else {
				Expression o = out.getValueAt(index);
				v = o.assign(value);
			}

			scope.getStatements().add(v);
		}

		return scope;
	}

	@Override
	public Process<Process<?, ?>, Runnable> optimize(ProcessContext ctx, Process<Process<?, ?>, Runnable> process) {
		if (process == (Supplier) getInputs().get(0))
			return process;

		return super.optimize(ctx, process);
	}

	@Override
	public Process<Process<?, ?>, Runnable> isolate(Process<Process<?, ?>, Runnable> process) {
		if (process == (Supplier) getInputs().get(0))
			return process;

		return super.isolate(process);
	}

	@Override
	public Assignment<T> generate(List<Process<?, ?>> children) {
		if (children.size() != 2) return this;

		Assignment result = new Assignment<>(memLength, (Supplier) children.get(0), (Supplier) children.get(1));

		if (getMetadata().getShortDescription() != null) {
			result.getMetadata().setShortDescription(getMetadata().getShortDescription());
		}

		return result;
	}

	@Override
	public String describe() {
		return getMetadata().getShortDescription() + " (" + getCount() + "x" + memLength + ")";
	}
}
