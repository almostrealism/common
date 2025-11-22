/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.arguments;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerSubstitution;
import org.almostrealism.hardware.MemoryData;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Evaluable} wrapper that applies producer substitutions during evaluation.
 *
 * <p>{@link AcceleratedSubstitutionEvaluable} wraps an {@link AcceleratedOperationContainer}
 * and manages a list of {@link io.almostrealism.relation.ProducerSubstitution} instances,
 * applying them before evaluation and clearing them afterward.</p>
 *
 * <h2>Deprecation Notice</h2>
 *
 * <p><strong>WARNING:</strong> This class is deprecated along with {@link AcceleratedOperationContainer}.
 * Modern code should use {@link ProcessArgumentMap} for producer substitution.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * AcceleratedOperation<Matrix> operation = compile(matmul(a, b));
 * AcceleratedOperationContainer<Matrix> container =
 *     new AcceleratedOperationContainer<>(operation);
 *
 * // Create substitution evaluable
 * AcceleratedSubstitutionEvaluable<Matrix> evaluable =
 *     new AcceleratedSubstitutionEvaluable<>(container);
 *
 * // Add substitutions
 * evaluable.addSubstitution(new ProducerSubstitution<>(a, x));
 * evaluable.addSubstitution(new ProducerSubstitution<>(b, y));
 *
 * // Evaluate with substitutions
 * Matrix result = evaluable.evaluate();  // Computes matmul(x, y)
 * }</pre>
 *
 * <h2>Substitution Lifecycle</h2>
 *
 * <p>Ensures substitutions are properly scoped to the evaluation:</p>
 *
 * <pre>{@code
 * @Override
 * public T evaluate(Object... args) {
 *     try {
 *         // 1. Apply substitutions to container
 *         container.setSubstitutions(substitutions);
 *
 *         // 2. Evaluate with substitutions active
 *         return container.evaluate(args);
 *     } finally {
 *         // 3. Always clear substitutions (even on exception)
 *         container.clearSubstitutions();
 *     }
 * }
 * }</pre>
 *
 * <h2>Multiple Substitutions</h2>
 *
 * <p>Build up a list of substitutions before evaluation:</p>
 *
 * <pre>{@code
 * AcceleratedSubstitutionEvaluable<T> evaluable = ...;
 *
 * // Substitute multiple producers
 * evaluable.addSubstitution(new ProducerSubstitution<>(inputA, newA));
 * evaluable.addSubstitution(new ProducerSubstitution<>(inputB, newB));
 * evaluable.addSubstitution(new ProducerSubstitution<>(inputC, newC));
 *
 * // All substitutions applied together
 * T result = evaluable.evaluate();
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Thread-safe through {@link AcceleratedOperationContainer}'s thread-local storage:</p>
 *
 * <pre>{@code
 * AcceleratedSubstitutionEvaluable<T> evaluable = ...;
 *
 * // Thread A
 * AcceleratedSubstitutionEvaluable<T> eval1 = new AcceleratedSubstitutionEvaluable<>(container);
 * eval1.addSubstitution(subsA);
 * T resultA = eval1.evaluate();  // Uses subsA
 *
 * // Thread B (concurrent)
 * AcceleratedSubstitutionEvaluable<T> eval2 = new AcceleratedSubstitutionEvaluable<>(container);
 * eval2.addSubstitution(subsB);
 * T resultB = eval2.evaluate();  // Uses subsB
 * }</pre>
 *
 * <h2>Exception Safety</h2>
 *
 * <p>Substitutions are cleared even if evaluation throws:</p>
 *
 * <pre>{@code
 * try {
 *     T result = evaluable.evaluate();
 * } catch (Exception e) {
 *     // Substitutions have been cleared from container
 *     // Container is ready for next evaluation
 * }
 * }</pre>
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Batch processing:</strong> Reuse compiled operation with different data batches</li>
 *   <li><strong>Parameter sweeps:</strong> Run same operation with different parameters</li>
 *   <li><strong>Dynamic graphs:</strong> Change computation inputs without recompilation</li>
 * </ul>
 *
 * <h2>Migration to ProcessArgumentMap</h2>
 *
 * <pre>{@code
 * // OLD (deprecated):
 * AcceleratedSubstitutionEvaluable<T> evaluable =
 *     new AcceleratedSubstitutionEvaluable<>(container);
 * evaluable.addSubstitution(new ProducerSubstitution<>(a, x));
 * T result = evaluable.evaluate();
 *
 * // NEW (recommended):
 * ProcessArgumentMap argMap = new ProcessArgumentMap(process, args);
 * argMap.put(keyA, x);  // Substitute at position
 * Evaluable<T> eval = argMap.getEvaluable(arg);
 * T result = eval.evaluate();
 * }</pre>
 *
 * @param <T> The {@link MemoryData} type produced by evaluation
 * @deprecated Use {@link ProcessArgumentMap} instead
 * @see AcceleratedOperationContainer
 * @see io.almostrealism.relation.ProducerSubstitution
 * @see ProcessArgumentMap
 */
@Deprecated
public class AcceleratedSubstitutionEvaluable<T extends MemoryData> implements Evaluable<T> {
	/** The wrapped operation container to which substitutions are applied during evaluation. */
	private AcceleratedOperationContainer<T> container;
	/** The list of producer substitutions to apply before each evaluation. */
	protected List<ProducerSubstitution<?>> substitutions;

	/**
	 * Creates a new substitution evaluable wrapping the given container.
	 *
	 * @param container the operation container to wrap
	 */
	public AcceleratedSubstitutionEvaluable(AcceleratedOperationContainer<T> container) {
		this.container = container;
		this.substitutions = new ArrayList<>();
	}

	/**
	 * Adds a producer substitution to be applied during evaluation.
	 *
	 * @param <V>          the type of value being substituted
	 * @param substitution the substitution to add
	 */
	public <V> void addSubstitution(ProducerSubstitution<V> substitution) {
		substitutions.add(substitution);
	}

	/**
	 * Evaluates the operation with all accumulated substitutions applied.
	 *
	 * <p>Substitutions are applied to the container before evaluation and
	 * cleared afterward, even if an exception occurs.</p>
	 *
	 * @param args the evaluation arguments
	 * @return the result of evaluation with substitutions applied
	 */
	@Override
	public T evaluate(Object... args) {
		try {
			container.setSubstitutions(substitutions);
			return container.evaluate(args);
		} finally {
			container.clearSubstitutions();
		}
	}
}
