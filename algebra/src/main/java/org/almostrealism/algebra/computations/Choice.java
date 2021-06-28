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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Computation;
import io.almostrealism.code.HybridScope;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.code.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Choice extends DynamicOperationComputationAdapter<Void> {
	private final ProducerComputation<Scalar> decision;
	private final List<Computation> choices;

	public Choice(ProducerComputation<Scalar> decision, List<Computation> choices) { ;
		this.decision = decision;
		this.choices = choices;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		ScopeLifecycle.prepareArguments(Stream.of(decision), map);
		choices.forEach(c -> c.prepareArguments(map));
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		ScopeLifecycle.prepareScope(Stream.of(decision), manager);
		choices.forEach(c -> c.prepareScope(manager));
	}

	@Override
	public Scope<Void> getScope() {
		HybridScope<Void> scope = new HybridScope<>(this);

		double interval = 1.0 / choices.size();

		Scope<Scalar> decisionScope = decision.getScope();
		ArrayVariable<?> decisionValue = decisionScope.getArgumentVariables().get(0);

		scope.getRequiredScopes().add(decisionScope);
		scope.code().accept(renderMethod(decisionScope.call()) + "\n");

		choices.stream().map(Computation::getScope).forEach(atomScope -> {
			atomScope.convertArgumentsToRequiredScopes();
			scope.getRequiredScopes().add(atomScope);
		});

		IntStream.range(1, scope.getRequiredScopes().size()).forEach(i -> {
			if (i > 1) {
				scope.code().accept("else ");
			}

			double val = i * interval;

			scope.code().accept("if (" + decisionValue.get(0).getExpression() + " < " + val + ") {\n");
			scope.code().accept("\t" + renderMethod(scope.getRequiredScopes().get(i).call()) + "\n");
			scope.code().accept("}");
		});

		scope.code().accept("\n");

		return scope;
	}
}
