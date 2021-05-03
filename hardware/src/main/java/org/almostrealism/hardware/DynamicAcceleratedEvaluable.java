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

package org.almostrealism.hardware;

import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.Variable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.cl.HardwareOperator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class DynamicAcceleratedEvaluable<I extends MemWrapper, O extends MemWrapper>
		extends DynamicAcceleratedOperation<MemWrapper>
		implements KernelizedEvaluable<O>, DestinationSupport<O> {
	private Supplier<O> destination;

	public DynamicAcceleratedEvaluable(Supplier<O> destination,
									   IntFunction<MemoryBank<O>> kernelDestination,
									   Supplier<Evaluable<? extends I>> inputArgs[],
									   Object additionalArguments[]) {
		this(destination, kernelDestination, AcceleratedEvaluable.producers(inputArgs, additionalArguments));
	}

	public DynamicAcceleratedEvaluable(Supplier<O> destination,
									   IntFunction<MemoryBank<O>> kernelDestination,
									   Supplier... inputArgs) {
		this(true, destination, kernelDestination, inputArgs);
	}

	public DynamicAcceleratedEvaluable(boolean kernel, Supplier<O> destination,
									   IntFunction<MemoryBank<O>> kernelDestination,
									   Supplier<Evaluable<? extends I>> inputArgs[],
									   Object additionalArguments[]) {
		this(kernel, destination, kernelDestination, AcceleratedEvaluable.producers(inputArgs, additionalArguments));
	}

	@SafeVarargs
	public DynamicAcceleratedEvaluable(boolean kernel, Supplier<O> destination,
									   IntFunction<MemoryBank<O>> kernelDestination,
									   Supplier<Evaluable<? extends I>>... inputArgs) {
		super(kernel, new Supplier[0]);
		setInputs(AcceleratedEvaluable.includeResult(new DynamicProducerForMemWrapper(args ->
				(getDestination() == null ? destination : getDestination()).get(), kernelDestination), inputArgs));
		init();
	}

	@Override
	public O evaluate(Object... args) { return (O) apply(args)[0]; }

	@Deprecated
	protected void writeVariables(Consumer<String> out) {
		writeVariables(out, new ArrayList<>());
	}

	@Deprecated
	protected void writeVariables(Consumer<String> out, List<Variable<?>> existingVariables) {
		getVariables().stream()
				.filter(v -> !existingVariables.contains(v)).forEach(var -> {
			if (var.getPhysicalScope() != null) {
				out.accept(var.getPhysicalScope() == PhysicalScope.LOCAL ? "__local" : "__global");
				out.accept(" ");
			}

			out.accept(getNumberTypeName());
			out.accept(" ");
			out.accept(var.getName());

			if (var.getExpression().getExpression() == null) {
				if (var.getArraySize() != null) {
					out.accept("[");
					out.accept(var.getArraySize().getExpression());
					out.accept("]");
				}
			} else {
				if (var.getArraySize() != null) {
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
		AcceleratedEvaluable.kernelEvaluate(this, destination, args, isKernel());
	}

	@Override
	protected MemWrapper[] getKernelArgs(MemoryBank args[]) {
		return getKernelArgs(getArguments(), args, 1);
	}

	@Override
	public void setDestination(Supplier<O> destination) { this.destination = destination; }

	@Override
	public Supplier<O> getDestination() { return this.destination; }

	@Override
	public MemoryBank<O> createKernelDestination(int size) {
		throw new RuntimeException("Not implemented");
	}
}
