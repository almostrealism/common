/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.relation;

import io.almostrealism.compute.Process;
import io.almostrealism.lifecycle.Destroyable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A {@link Producer} represents a computational operation that can produce an {@link Evaluable}
 * which, when executed, generates a result of type {@code T}.
 *
 * <p>{@link Producer} is the core abstraction in the Almost Realism computation framework that
 * separates <b>computation description</b> from <b>computation execution</b>. This separation
 * enables:</p>
 * <ul>
 *   <li>Static analysis of computation graphs before execution</li>
 *   <li>Optimization and fusion of operations</li>
 *   <li>Compilation to GPU kernels or other accelerated backends</li>
 *   <li>Deferred evaluation until results are actually needed</li>
 * </ul>
 *
 * <h2>Two-Phase Execution Model</h2>
 * <ol>
 *   <li><b>Description Phase:</b> Build a computation graph by combining {@link Producer}s
 *       using operations like {@link Composition} and {@link Factor}</li>
 *   <li><b>Execution Phase:</b> Call {@link #get()} to obtain an {@link Evaluable},
 *       then {@link Evaluable#evaluate(Object...)} to produce results</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Build computation graph (description phase)
 * Producer<Tensor> inputA = ...;
 * Producer<Tensor> inputB = ...;
 * Producer<Tensor> sum = operations.add(inputA, inputB);
 *
 * // Execute computation (execution phase)
 * Evaluable<Tensor> evaluable = sum.get();
 * Tensor result = evaluable.evaluate();
 * }</pre>
 *
 * <h2>Relationship to Other Types</h2>
 * <ul>
 *   <li>{@link Evaluable} - The executable form produced by {@link #get()}</li>
 *   <li>{@link Computable} - Marker interface indicating computation requirement</li>
 *   <li>{@link Node} - Enables participation in computation graphs</li>
 *   <li>{@link Factor} - Transforms one {@link Producer} into another</li>
 *   <li>{@link Composition} - Combines two {@link Producer}s into one</li>
 * </ul>
 *
 * @param <T> the type of the computation result
 *
 * @see Evaluable
 * @see Computable
 * @see Factor
 * @see Composition
 *
 * @author Michael Murray
 */
public interface Producer<T> extends Supplier<Evaluable<? extends T>>, Computable, Node, Destroyable {
	/**
	 * Creates and returns an {@link Evaluable} that can execute this computation.
	 *
	 * <p>This method compiles the computation represented by this {@link Producer}
	 * into an executable form. The returned {@link Evaluable} can be invoked
	 * multiple times with different arguments.</p>
	 *
	 * <p>Implementations may perform optimization, compilation to native code,
	 * or GPU kernel generation during this method.</p>
	 *
	 * @return an {@link Evaluable} that executes this computation
	 */
	@Override
	Evaluable<T> get();

	/**
	 * Obtains an {@link Evaluable} configured to write results directly into
	 * the specified destination.
	 *
	 * <p>This method enables in-place computation, avoiding allocation of
	 * new result objects when the destination is pre-allocated.</p>
	 *
	 * @param destination the object to receive computation results
	 * @return an {@link Evaluable} that writes to the destination
	 */
	default Evaluable<T> into(Object destination) {
		return get().into(destination);
	}

	/**
	 * Optimizes this {@link Producer} and evaluates the result in a single step.
	 *
	 * <p>This method applies optimization transformations to the computation
	 * graph before evaluation. It is useful for one-shot evaluations where
	 * the overhead of optimization is acceptable.</p>
	 *
	 * @param args arguments to pass to the evaluation
	 * @return the computed result
	 *
	 * @see Process#optimized(Supplier)
	 */
	default T evaluateOptimized(Object... args) {
		return Process.optimized(this).get().evaluate(args);
	}

	/**
	 * Evaluates this computation with the specified arguments.
	 *
	 * <p>This is a convenience method that combines {@link #get()} and
	 * {@link Evaluable#evaluate(Object...)} into a single call. For repeated
	 * evaluations, consider caching the {@link Evaluable} returned by
	 * {@link #get()}.</p>
	 *
	 * @param args arguments to pass to the evaluation
	 * @return the computed result
	 */
	default T evaluate(Object... args) {
		return get().evaluate(args);
	}

	/**
	 * Returns the {@link Producer} whose value this one presents without transforming it,
	 * which is the producer to compare when deciding whether two producers refer to the
	 * same value. A producer that only reinterprets or redirects another producer's value
	 * (a reshape, a destination wrapper) returns the match root of that producer; every
	 * other producer is its own match root.
	 *
	 * @return the producer that identifies the value of this one
	 */
	default Producer<?> getMatchRoot() {
		return this;
	}

	/**
	 * Returns {@code true} if this producer introduces a value into a computation graph
	 * (a provided value, a kernel argument, an input placeholder) rather than deriving it
	 * from other producers. Two such producers refer to the same value only if they match
	 * each other directly, so neither can contain the other as an input.
	 *
	 * @return {@code true} if this producer is a source of values with no inputs to match
	 */
	default boolean isMatchRoot() {
		return false;
	}

	/**
	 * Returns {@code true} if this producer refers to the same value as {@code other}:
	 * their {@link #getMatchRoot() match roots} {@link Computable#matches(Computable) match}.
	 *
	 * @param other the producer to compare against
	 * @return {@code true} if both producers refer to the same value
	 */
	default boolean refersTo(Producer<?> other) {
		return getMatchRoot().matches(other.getMatchRoot());
	}

	/**
	 * Returns {@code true} if this producer, or any producer among its inputs at any
	 * depth, {@link #refersTo(Producer) refers to} the target.
	 *
	 * @param target the producer to search for
	 * @return {@code true} if the target is found
	 */
	default boolean containsMatch(Producer<?> target) {
		return refersTo(target) || matchableInputs().anyMatch(in -> in.containsMatch(target));
	}

	/**
	 * Returns the producers this producer is computed from, which are searched when
	 * matching a target against its inputs. By default these are the producers among this
	 * producer's {@link Process#getChildren() process children}. A producer that keeps
	 * its inputs elsewhere, or whose process children present some inputs wrapped rather
	 * than as producers, must override this method to expose them, or it will be reported
	 * as independent of those inputs.
	 *
	 * @return the inputs of this producer, empty when it has none
	 */
	default Stream<? extends Producer<?>> matchableInputs() {
		if (!(this instanceof Process)) return Stream.empty();

		Collection<? extends Process<?, ?>> children = ((Process<?, ?>) this).getChildren();
		if (children == null) return Stream.empty();

		return children.stream()
				.filter(child -> child instanceof Producer)
				.map(child -> (Producer<?>) child);
	}

	/**
	 * Returns every one of this producer's {@link #matchableInputs() inputs} that
	 * {@link #containsMatch(Producer) contains} the target. Used in automatic
	 * differentiation to find the inputs through which this producer depends on a variable.
	 *
	 * @param target the producer to search for
	 * @return the matching inputs, which is empty when this producer has no inputs
	 */
	default List<Producer<T>> matchingInputs(Producer<?> target) {
		return matchableInputs()
				.filter(in -> in.containsMatch(target))
				.map(in -> (Producer<T>) in)
				.collect(Collectors.toList());
	}

	/**
	 * Returns the single input of this producer that {@link #containsMatch(Producer) contains}
	 * the target.
	 *
	 * @param target the producer to search for
	 * @return the matching input if exactly one exists, an empty {@link Optional} if none
	 *         does, or {@code null} if several do
	 */
	default Optional<Producer<T>> matchInput(Producer<?> target) {
		List<Producer<T>> matched = matchingInputs(target);

		if (matched.isEmpty()) {
			return Optional.empty();
		} else if (matched.size() == 1) {
			return Optional.of(matched.get(0));
		}

		// TODO(review): a null Optional encodes "ambiguous"; consider a result type that names the three cases
		return null;
	}

	/**
	 * Returns {@code true} if it can be determined that this producer does not depend on
	 * the target. A producer that {@link #refersTo(Producer) refers to} the target depends
	 * on it. Otherwise both are reduced to their {@link #getMatchRoot() match roots}: two
	 * {@link #isMatchRoot() match roots} are independent, and any other producer is
	 * independent of the target when none of its inputs contains it. The result is
	 * {@code false} whenever independence cannot be established, including when several
	 * inputs contain the target.
	 *
	 * @param target the producer to search for
	 * @return {@code true} if this producer is known not to depend on the target
	 */
	default boolean cannotMatch(Producer<?> target) {
		if (refersTo(target)) return false;

		Producer<?> root = getMatchRoot();
		Producer<?> targetRoot = target.getMatchRoot();

		if (root.isMatchRoot() && targetRoot.isMatchRoot()) {
			return true;
		}

		Optional<? extends Producer<?>> matched = root.matchInput(targetRoot);
		return matched != null && matched.isEmpty();
	}
}
