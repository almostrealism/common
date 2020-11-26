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

package org.almostrealism.hardware;

import io.almostrealism.code.Variable;
import org.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class DynamicAcceleratedProducer<I extends MemWrapper, O extends MemWrapper> extends DynamicAcceleratedOperation<MemWrapper> implements KernelizedEvaluable<O> {
	public DynamicAcceleratedProducer(Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>> inputArgs[], Object additionalArguments[]) {
		this(result, AcceleratedProducer.producers(inputArgs, additionalArguments));
	}

	public DynamicAcceleratedProducer(Supplier<Evaluable<? extends O>> result, Supplier... inputArgs) {
		this(true, result, inputArgs);
	}

	public DynamicAcceleratedProducer(boolean kernel, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>> inputArgs[], Object additionalArguments[]) {
		this(kernel, result, AcceleratedProducer.producers(inputArgs, additionalArguments));
	}

	public DynamicAcceleratedProducer(boolean kernel, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		super(kernel, AcceleratedProducer.includeResult(result, inputArgs));
		init();
	}

	@Override
	public O evaluate(Object... args) { return (O) apply(args)[0]; }

	protected void writeVariables(Consumer<String> out) {
		writeVariables(out, new ArrayList<>());
	}

	protected void writeVariables(Consumer<String> out, List<Variable<?>> existingVariables) {
		getVariables().stream()
				.filter(v -> !existingVariables.contains(v)).forEach((Consumer<Variable<?>>) var -> {
			if (var.getAnnotation() != null) {
				out.accept(var.getAnnotation());
				out.accept(" ");
			}

			out.accept(getNumberType());
			out.accept(" ");
			out.accept(var.getName());

			if (var.getExpression().getExpression() == null) {
				if (var.getArraySize() >= 0) {
					out.accept("[");
					out.accept(String.valueOf(var.getArraySize()));
					out.accept("]");
				}
			} else {
				if (var.getArraySize() >= 0) {
					throw new RuntimeException("Not implemented");
				} else {
					out.accept(" = ");
					out.accept(String.valueOf(var.getExpression().getValue()));
				}
			}

			out.accept(";\n");
		});
	}

	/**
	 * If {@link #isKernel()} returns true, this method will pass the
	 * destination and the argument {@link MemoryBank}s to the
	 * {@link HardwareOperator}. Otherwise, {@link #evaluate(Object[])}
	 * will be called sequentially and the result will be added to the
	 * destination.
	 */
	@Override
	public void kernelEvaluate(MemoryBank destination, MemoryBank args[]) {
		AcceleratedProducer.kernelEvaluate(this, destination, args, isKernel());
	}

	@Override
	protected MemoryBank[] getKernelArgs(MemoryBank args[]) {
		return getKernelArgs(getArguments(), args, 1);
	}

	@Override
	public MemoryBank<O> createKernelDestination(int size) {
		throw new RuntimeException("Not implemented");
	}
}
