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

package org.almostrealism.hardware.kernel;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryDataDestination;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class KernelTraversalOperationGenerator implements KernelTraversalProvider, ConsoleFeatures {
	public static boolean enableGeneration = true;
	public static boolean enableVerbose = false;
	public static int defaultMaxEntries = 128;

	private LanguageOperations lang;

	private int count;
	private boolean fixed;
	private Function<Producer<?>, ArrayVariable<?>> variableFactory;
	private Map<String, TraversalOperation> operations;
	private Map<String, ArrayVariable> variables;

	protected KernelTraversalOperationGenerator(int count, boolean fixed, Function<Producer<?>, ArrayVariable<?>> variableFactory) {
		this.lang = new LanguageOperationsStub();
		this.count = count;
		this.fixed = fixed;
		this.variableFactory = variableFactory;
		this.operations = new IdentityHashMap<>();
		this.variables = new IdentityHashMap<>();
	}

	@Override
	public Expression<?> generateReordering(Expression<?> expression) {
		if (!enableGeneration || !fixed) return expression;

		String e = expression.getExpression(lang);
		ArrayVariable<?> variable = variables.get(e);
		if (variable != null) return variable.referenceAbsolute(new KernelIndex());

		if (operations.size() >= defaultMaxEntries) {
			if (enableVerbose)
				warn("Reached max operations");
			return expression;
		}

		TraversalOperation<?> operation = new TraversalOperation<>();
		IntStream.range(0, count)
				.mapToObj(i -> expression.withKernel(i).getSimplified())
				.forEach(operation.getExpressions()::add);
		operations.put(e, operation);

		variable = variableFactory.apply((Producer) operation.isolate());
		variables.put(e, variable);
		return variable.referenceAbsolute(new KernelIndex());
	}

	@Override
	public Console console() { return AcceleratedOperation.console; }

	protected class TraversalOperation<T extends MemoryData> extends ProducerComputationBase<T, T>
			implements MemoryDataComputation<T>, DestinationSupport<T>, ComputerFeatures {
		private List<Expression> expressions;
		private Supplier<T> destination;

		public TraversalOperation() {
			this.expressions = new ArrayList<>();
			setInputs(new MemoryDataDestination(this, i -> new Bytes(expressions.size())));
			init();
		}

		protected List<Expression> getExpressions() { return expressions; }

		@Override
		public void setDestination(Supplier<T> destination) { this.destination = destination; }

		@Override
		public Supplier<T> getDestination() { return destination; }

		@Override
		public int getMemLength() { return expressions.size(); }

		@Override
		public int getCount() { return 1; }

		@Override
		public boolean isFixedCount() { return true; }

		@Override
		public Scope<T> getScope() {
			Scope<T> scope = super.getScope();
			ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

			for (int i = 0; i < getMemLength(); i++) {
				scope.getVariables().add(output.ref(i).assign(expressions.get(i)));
			}

			return scope;
		}

		@Override
		public Evaluable<T> get() {
			ComputeContext<MemoryData> ctx = Hardware.getLocalHardware().getComputer().getContext(this);
			AcceleratedComputationEvaluable<T> ev = new AcceleratedComputationEvaluable<>(ctx, this);
			ev.setKernelStructureSupported(false);
			ev.setDestinationFactory(i -> (Multiple) new Bytes(expressions.size()));
			return ev;
		}
	}

	public static KernelTraversalOperationGenerator create(Computation<?> c, Function<Producer<?>, ArrayVariable<?>> variableFactory) {
		int count = ParallelProcess.count(c);
		boolean fixed = ParallelProcess.isFixedCount(c);
		return new KernelTraversalOperationGenerator(count, fixed, variableFactory);
	}
}