/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.code.Computation;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;

/**
 * {@link OperationComputationAdapter} that generates a fixed-iteration for-loop in compiled code.
 *
 * <p>{@link Loop} wraps a {@link Computation} and repeats it a fixed number of times,
 * generating a for-loop structure in the compiled {@link Scope}.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * // Create computation to repeat
 * Computation<Void> update = updateWeights();
 *
 * // Loop 10 times
 * Loop loop = new Loop(update, 10);
 *
 * // Compile to scope
 * Scope<Void> scope = loop.getScope(context);
 * }</pre>
 *
 * <h2>Generated Code Structure</h2>
 *
 * <p>For a loop with {@code iterations = 5}:</p>
 *
 * <pre>{@code
 * void loop_x5() {
 *     for (int loop_i = 1; loop_i < 5; loop_i += 1) {
 *         // Atom computation body
 *         update();
 *     }
 * }
 * }</pre>
 *
 * <h2>Loop Configuration</h2>
 *
 * <ul>
 *   <li><strong>Index variable:</strong> Named using {@code NameProvider} prefix + "_i"</li>
 *   <li><strong>Start value:</strong> 1 (not 0)</li>
 *   <li><strong>Condition:</strong> {@code i < iterations}</li>
 *   <li><strong>Increment:</strong> 1 per iteration</li>
 * </ul>
 *
 * <h2>Nested Loops</h2>
 *
 * <pre>{@code
 * // Inner loop (j)
 * Computation<Void> innerOp = matrixUpdate(i, j);
 * Loop innerLoop = new Loop(innerOp, colCount);
 *
 * // Outer loop (i)
 * Loop outerLoop = new Loop(innerLoop, rowCount);
 *
 * // Generates:
 * // for (int i = 1; i < rowCount; i++) {
 * //     for (int j = 1; j < colCount; j++) {
 * //         matrixUpdate(i, j);
 * //     }
 * // }
 * }</pre>
 *
 * <h2>Argument and Scope Preparation</h2>
 *
 * <p>Delegates preparation to the atom computation:</p>
 *
 * <pre>{@code
 * @Override
 * public void prepareArguments(ArgumentMap map) {
 *     super.prepareArguments(map);
 *     atom.prepareArguments(map);  // Forward to atom
 * }
 *
 * @Override
 * public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
 *     super.prepareScope(manager, context);
 *     atom.prepareScope(manager, context);  // Forward to atom
 * }
 * }</pre>
 *
 * <h2>Count Propagation</h2>
 *
 * <p>Loop count is derived from the atom computation:</p>
 *
 * <pre>{@code
 * @Override
 * public long getCountLong() {
 *     return atom instanceof Countable ?
 *         ((Countable) atom).getCountLong() : 1;
 * }
 * }</pre>
 *
 * <h2>Future Enhancement</h2>
 *
 * <p><strong>TODO:</strong> This class should extend {@link io.almostrealism.scope.Repeated}
 * to better integrate with the scope hierarchy.</p>
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Training iterations:</strong> Repeat gradient descent steps</li>
 *   <li><strong>Time steps:</strong> Simulate multiple time increments</li>
 *   <li><strong>Refinement:</strong> Iterative improvement algorithms</li>
 *   <li><strong>Unrolled recursion:</strong> Fixed-depth recursive computations</li>
 * </ul>
 *
 * @see OperationComputationAdapter
 * @see io.almostrealism.scope.Repeated
 * @see ExpressionFeatures
 */
// TODO  Should extend Repeated
public class Loop extends OperationComputationAdapter<Void> implements ExpressionFeatures {
	private final Computation atom;
	private final int iterations;

	public Loop(Computation<Void> atom, int iterations) {
		this.atom = atom;
		this.iterations = iterations;
		init();
	}

	@Override
	public String getName() {
		return "Loop x" + iterations;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		atom.prepareArguments(map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		atom.prepareScope(manager, context);
	}

	@Override
	protected List<Computation<?>> getDependentComputations() {
		return List.of(atom);
	}

	@Override
	public long getCountLong() {
		return atom instanceof Countable ? ((Countable) atom).getCountLong() : 1;
	}

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		Repeated<Void> scope = new Repeated<>(getFunctionName(), getMetadata());
		Variable<Integer, ?> i = Variable.integer(getNameProvider().getVariablePrefix() + "_i");
		scope.setInterval(e(1));
		scope.setIndex(i);
		scope.setCondition(i.ref().lessThan(e(iterations)));
		scope.add(atom.getScope(context));
		return scope;
	}
}
