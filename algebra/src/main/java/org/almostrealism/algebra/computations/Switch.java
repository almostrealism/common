/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.Computation;
import io.almostrealism.scope.Cases;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;
import java.util.stream.IntStream;

/**
 * A computation that executes one of multiple computation branches based on a decision value.
 *
 * <p>
 * {@link Switch} implements a switch/case-like control flow where:
 * <ul>
 *   <li>A decision value (typically in range [0, 1]) determines which branch to execute</li>
 *   <li>The value range is divided into equal intervals, one for each choice</li>
 *   <li>Each choice is a full {@link Computation} that can have its own logic</li>
 * </ul>
 *
 * <p>
 * This differs from {@link Choice} in that:
 * <ul>
 *   <li>{@link Choice} selects from pre-computed values (data selection)</li>
 *   <li>{@link Switch} executes one of multiple computations (control flow)</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Create switch with 3 computation branches
 * Producer<PackedCollection> decision = c(0.4);  // Will execute branch 1
 *
 * List<Computation<?>> branches = Arrays.asList(
 *     computation0,  // Executes when decision in [0.0, 0.33)
 *     computation1,  // Executes when decision in [0.33, 0.66)
 *     computation2   // Executes when decision in [0.66, 1.0]
 * );
 *
 * Switch switchComp = new Switch(decision, branches);
 * }</pre>
 *
 * @author  Michael Murray
 * @see Choice
 */
public class Switch extends OperationComputationAdapter<PackedCollection> implements ExpressionFeatures {
	private final List<Computation<?>> choices;

	/**
	 * Creates a new Switch computation.
	 *
	 * @param decision  producer providing the decision value that determines which branch to execute
	 * @param choices  list of computation branches to choose from
	 */
	public Switch(Producer<PackedCollection> decision, List<Computation<?>> choices) {
		super(new Producer[] { decision });
		this.choices = choices;
	}

	/**
	 * Returns the list of computation branches that are dependencies of this switch.
	 *
	 * @return the list of choice computations
	 */
	@Override
	protected List<Computation<?>> getDependentComputations() {
		return choices;
	}

	/**
	 * Prepares arguments for this switch and all choice computations.
	 *
	 * @param map  the argument map
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		choices.forEach(c -> c.prepareArguments(map));
	}

	/**
	 * Prepares the scope for this switch and all choice computations.
	 *
	 * @param manager  the scope input manager
	 * @param context  the kernel structure context
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		choices.forEach(c -> c.prepareScope(manager, context));
	}

	/**
	 * Generates the scope containing the switch/case logic.
	 *
	 * <p>
	 * Creates a {@link Cases} scope where:
	 * <ul>
	 *   <li>The decision range [0, 1] is divided into equal intervals</li>
	 *   <li>Each interval corresponds to one choice computation</li>
	 *   <li>Conditions are generated as: decision <= (i+1) * interval</li>
	 * </ul>
	 * </p>
	 *
	 * @param context  the kernel structure context
	 * @return the cases scope containing all branches
	 */
	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		Cases<Void> scope = new Cases<>(getName(), getMetadata());

		double interval = 1.0 / choices.size();

		ArrayVariable<?> decisionValue = getArgument(0);

		IntStream.range(0, choices.size()).forEach(i -> {
			double val = (i + 1) * interval;
			scope.getConditions().add(decisionValue.valueAt(0).lessThanOrEqual(e(val)));
			scope.getChildren().add((Scope) choices.get(i).getScope(context));
		});

		return scope;
	}
}
