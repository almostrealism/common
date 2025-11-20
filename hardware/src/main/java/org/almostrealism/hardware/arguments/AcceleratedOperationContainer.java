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

package org.almostrealism.hardware.arguments;

import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ProducerSubstitution;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.function.Supplier;

/**
 * Thread-safe container for {@link AcceleratedOperation} with producer substitution support.
 *
 * <p>{@link AcceleratedOperationContainer} wraps an {@link AcceleratedOperation} and provides
 * thread-local substitution of producers, enabling operation reuse with different inputs.</p>
 *
 * <h2>Deprecation Notice</h2>
 *
 * <p><strong>WARNING:</strong> This class is deprecated in favor of {@link ProcessArgumentMap}
 * with {@link org.almostrealism.hardware.instructions.ScopeInstructionsManager}. The thread-local
 * substitution approach has been superseded by position-based argument mapping.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * AcceleratedOperation<Matrix> operation = compile(matmul(a, b));
 * AcceleratedOperationContainer<Matrix> container =
 *     new AcceleratedOperationContainer<>(operation);
 *
 * // Set substitutions for current thread
 * container.setSubstitutions(substitutions);
 *
 * // Evaluate with substituted producers
 * Matrix result = container.evaluate();
 *
 * // Clear thread-local substitutions
 * container.clearSubstitutions();
 * }</pre>
 *
 * <h2>Thread-Local Substitution</h2>
 *
 * <p>Substitutions are stored per-thread, allowing concurrent execution with different inputs:</p>
 *
 * <pre>{@code
 * AcceleratedOperationContainer<Matrix> container = ...;
 *
 * // Thread A
 * container.setSubstitutions(subsA);
 * Matrix resultA = container.evaluate();  // Uses subsA
 *
 * // Thread B (concurrent)
 * container.setSubstitutions(subsB);
 * Matrix resultB = container.evaluate();  // Uses subsB
 * }</pre>
 *
 * <h2>Producer Substitution</h2>
 *
 * <p>Replace producers dynamically without recompilation:</p>
 *
 * <pre>{@code
 * // Original operation: matmul(a, b)
 * AcceleratedOperation<Matrix> op = compile(matmul(a, b));
 * AcceleratedOperationContainer<Matrix> container =
 *     new AcceleratedOperationContainer<>(op);
 *
 * // Substitute different inputs
 * List<ProducerSubstitution<?>> subs = List.of(
 *     new ProducerSubstitution<>(a, x),  // Replace a with x
 *     new ProducerSubstitution<>(b, y)   // Replace b with y
 * );
 *
 * container.setSubstitutions(subs);
 * Matrix result = container.evaluate();  // Computes matmul(x, y)
 * }</pre>
 *
 * <h2>ProcessArgumentEvaluator Implementation</h2>
 *
 * <p>Resolves {@link ArrayVariable} arguments using substitutions:</p>
 *
 * <pre>{@code
 * @Override
 * public <V> Evaluable<? extends Multiple<V>> getEvaluable(ArrayVariable<V> argument) {
 *     Supplier<Evaluable<? extends V>> producer = argument.getProducer();
 *
 *     // Search substitutions
 *     for (ProducerSubstitution<?> sub : substitutions.get()) {
 *         if (sub.match(producer)) {
 *             return sub.getReplacement().get();
 *         }
 *     }
 *
 *     // No substitution found, use original
 *     return producer.get();
 * }
 * }</pre>
 *
 * <h2>Details Factory Reset</h2>
 *
 * <p>When substitutions change, resets the operation's details factory:</p>
 *
 * <pre>{@code
 * public void setSubstitutions(List<ProducerSubstitution<?>> substitutions) {
 *     this.substitutions.set(substitutions);
 *     this.operation.getDetailsFactory().reset();  // Clear cached details
 * }
 * }</pre>
 *
 * <h2>Count Delegation</h2>
 *
 * <p>Delegates {@link Countable} methods to wrapped operation:</p>
 *
 * <pre>{@code
 * long count = container.getCountLong();  // Delegates to operation.getCountLong()
 * }</pre>
 *
 * <h2>Evaluation Delegation</h2>
 *
 * <p>Evaluates the wrapped operation if it's an {@link Evaluable}:</p>
 *
 * <pre>{@code
 * @Override
 * public T evaluate(Object... args) {
 *     if (operation instanceof Evaluable<?>) {
 *         return ((Evaluable<T>) operation).evaluate(args);
 *     }
 *     throw new UnsupportedOperationException();
 * }
 * }</pre>
 *
 * <h2>Migration to ProcessArgumentMap</h2>
 *
 * <pre>{@code
 * // OLD (deprecated):
 * AcceleratedOperationContainer<T> container =
 *     new AcceleratedOperationContainer<>(operation);
 * container.setSubstitutions(substitutions);
 * T result = container.evaluate();
 *
 * // NEW (recommended):
 * ProcessArgumentMap argMap = new ProcessArgumentMap(process, args);
 * argMap.putSubstitutions(newProcess);
 * Evaluable<T> evaluable = argMap.getEvaluable(arg);
 * T result = evaluable.evaluate();
 * }</pre>
 *
 * @param <T> The {@link MemoryData} type produced by the operation
 * @deprecated Use {@link ProcessArgumentMap} instead
 * @see ProcessArgumentMap
 * @see io.almostrealism.relation.ProducerSubstitution
 * @see AcceleratedOperation
 */
@Deprecated
public class AcceleratedOperationContainer<T extends MemoryData>
		implements Countable, Evaluable<T>, ProcessArgumentEvaluator {
	private AcceleratedOperation<T> operation;
	private ThreadLocal<List<ProducerSubstitution<?>>> substitutions;

	public AcceleratedOperationContainer(AcceleratedOperation<T> operation) {
		this.operation = operation;
		this.substitutions = new ThreadLocal<>();
	}

	public void setSubstitutions(List<ProducerSubstitution<?>> substitutions) {
		this.substitutions.set(substitutions);
		this.operation.getDetailsFactory().reset();
	}

	public void clearSubstitutions() {
		this.substitutions.remove();
	}

	@Override
	public T evaluate(Object... args) {
		if (operation instanceof Evaluable<?>) {
			return ((Evaluable<T>) operation).evaluate(args);
		}

		throw new UnsupportedOperationException();
	}

	@Override
	public long getCountLong() { return operation.getCountLong(); }

	@Override
	public <V> Evaluable<? extends Multiple<V>> getEvaluable(ArrayVariable<V> argument) {
		return getEvaluable(argument.getProducer());
	}

	public <V> Evaluable<? extends V> getEvaluable(Supplier<Evaluable<? extends V>> producer) {
		List<ProducerSubstitution<?>> subs = substitutions.get();

		if (subs != null) {
			for (ProducerSubstitution<?> s : subs) {
				if (s.match(producer)) {
					return (Evaluable) s.getReplacement().get();
				}
			}
		}

		return producer.get();
	}
}
