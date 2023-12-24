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

package org.almostrealism.hardware;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.mem.AcceleratedProcessDetails;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

@Deprecated
public abstract class DynamicAcceleratedEvaluable<I extends MemoryData, O extends MemoryData>
		extends DynamicAcceleratedOperation<MemoryData>
		implements KernelizedEvaluable<O>, DestinationSupport<O> {
	private Supplier<O> destination;

	public DynamicAcceleratedEvaluable(ComputeContext<MemoryData> context,
									   Supplier<O> destination,
									   IntFunction<MemoryBank<O>> kernelDestination,
									   Supplier... inputArgs) {
		this(context, true, destination, kernelDestination, inputArgs);
	}

	@SafeVarargs
	public DynamicAcceleratedEvaluable(ComputeContext<MemoryData> context, boolean kernel,
									   Supplier<O> destination,
									   IntFunction<MemoryBank<O>> kernelDestination,
									   Supplier<Evaluable<? extends I>>... inputArgs) {
		super(context, kernel, new Supplier[0]);
		setInputs(AcceleratedEvaluable.includeResult(new DynamicProducerForMemoryData(args ->
				(getDestination() == null ? destination : getDestination()).get(), kernelDestination), inputArgs));
		init();
	}

	@Override
	public O evaluate(Object... args) {
		AcceleratedProcessDetails process = apply(null, args);
		waitFor(process.getSemaphore());
		return (O) process.getOriginalArguments()[0];
	}

	@Deprecated
	protected void writeVariables(Consumer<String> out, List<Variable<?, ?>> existingVariables, LanguageOperations lang) {
		getVariables().stream()
				.filter(v -> !existingVariables.contains(v)).forEach(var -> {
			if (var.getPhysicalScope() != null) {
				out.accept(var.getPhysicalScope() == PhysicalScope.LOCAL ? "__local" : "__global");
				out.accept(" ");
			}

			out.accept(lang.getPrecision().typeName());
			out.accept(" ");
			out.accept(var.getDestination().getSimpleExpression(lang));

			if (var.getExpression().isNull()) {
				if (var.getArraySize() != null) {
					out.accept("[");
					out.accept(var.getArraySize().getSimpleExpression(lang));
					out.accept("]");
				}
			} else {
				if (var.getArraySize() != null) {
					throw new RuntimeException("Not implemented");
				} else {
					out.accept(" = ");
					out.accept(var.getExpression().getSimpleExpression(lang));
				}
			}

			out.accept(";\n");
		});
	}

	@Override
	public Variable getOutputVariable() { return getArgument(null, 0); }

	@Override
	public void setDestination(Supplier<O> destination) { this.destination = destination; }

	@Override
	public Supplier<O> getDestination() { return this.destination; }
}
