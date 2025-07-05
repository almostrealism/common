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
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

public class KernelTraversalOperationGenerator implements KernelTraversalProvider, Destroyable, ConsoleFeatures {
	public static boolean enableGeneration = true;
	public static boolean enableVerbose = false;
	public static int defaultMaxEntries = 128;
	public static int minimumChildren = 16;

	private LanguageOperations lang;

	private int count;
	private boolean fixed;
	private Function<Producer<?>, ArrayVariable<?>> variableFactory;
	private Map<String, KernelTraversalOperation> operations;
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
		long start = System.nanoTime();

		try {
			if (!enableGeneration || !fixed) return expression;
			if (expression.getChildren().size() < minimumChildren) return expression;

			String e = expression.getExpression(lang);
			ArrayVariable<?> variable = variables.get(e);
			if (variable != null) return variable.referenceAbsolute(new KernelIndex());

			if (operations.size() >= defaultMaxEntries) {
				if (enableVerbose)
					warn("Reached max operations");
				return expression;
			}

			KernelTraversalOperation<?> operation = new KernelTraversalOperation<>();
			IntStream.range(0, count)
					.mapToObj(i -> expression.withIndex(new KernelIndex(), i).getSimplified())
					.forEach(operation.getExpressions()::add);
			operations.put(e, operation);

			variable = variableFactory.apply((Producer) operation.isolate());
			variables.put(e, variable);
			return variable.referenceAbsolute(new KernelIndex());
		} finally {
			timing.addEntry(String.valueOf(count), System.nanoTime() - start);
		}
	}

	@Override
	public void destroy() {
		operations.forEach((k, v) -> v.destroy());
		operations.clear();
	}

	@Override
	public Console console() { return AcceleratedOperation.console; }

	public static KernelTraversalOperationGenerator create(Computation<?> c, Function<Producer<?>, ArrayVariable<?>> variableFactory) {
		int count = Countable.count(c);
		boolean fixed = Countable.isFixedCount(c);
		return new KernelTraversalOperationGenerator(count, fixed, variableFactory);
	}
}
