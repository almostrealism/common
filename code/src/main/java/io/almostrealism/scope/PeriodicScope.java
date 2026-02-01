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

package io.almostrealism.scope;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.Statement;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.profile.OperationMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A {@link Scope} that generates counter-based periodic execution in compiled code.
 *
 * <p>{@link PeriodicScope} increments a counter expression on every invocation
 * and conditionally executes its child scopes when the counter reaches a
 * specified period. After execution, the counter resets to zero.</p>
 *
 * <h2>Generated Code Structure</h2>
 * <pre>{@code
 * // counter increment
 * counter[0] = counter[0] + 1;
 * if (counter[0] >= period) {
 *     // child scopes (atom body)
 *     counter[0] = 0;
 * }
 * }</pre>
 *
 * <h2>Typical Usage</h2>
 * <p>A {@link PeriodicScope} is typically nested inside a {@link Repeated}
 * (for-loop) scope, enabling "every N ticks" logic within a compiled loop:</p>
 * <pre>{@code
 * for (int i = 1; i < 44100; i += 1) {
 *     counter[0] = counter[0] + 1;
 *     if (counter[0] >= 1024) {
 *         renderBatch();
 *         counter[0] = 0;
 *     }
 *     // other tick operations
 * }
 * }</pre>
 *
 * <h2>Counter State</h2>
 * <p>The counter is an {@link Expression} referencing persistent memory
 * (typically an element of a {@link io.almostrealism.scope.ArrayVariable}
 * backed by a {@code PackedCollection(1)}). This allows the counter to
 * survive across compiled invocations.</p>
 *
 * @param <T> the type of value returned by this scope
 *
 * @see Repeated
 * @see Scope
 *
 * @author Michael Murray
 */
public class PeriodicScope<T> extends Scope<T> {
	private Expression<?> counter;
	private int period;

	/**
	 * Creates an empty {@link PeriodicScope} with no name or metadata.
	 */
	public PeriodicScope() { }

	/**
	 * Creates a {@link PeriodicScope} with the specified name.
	 *
	 * @param name the unique identifier for this scope
	 */
	public PeriodicScope(String name) {
		this();
		setName(name);
	}

	/**
	 * Creates a {@link PeriodicScope} with the specified name and metadata.
	 *
	 * @param name     the unique identifier for this scope
	 * @param metadata operation metadata for profiling and debugging
	 * @throws IllegalArgumentException if metadata is null
	 */
	public PeriodicScope(String name, OperationMetadata metadata) {
		this(name);
		setMetadata(new OperationMetadata(metadata));

		if (metadata == null)
			throw new IllegalArgumentException();
	}

	/**
	 * Returns the counter expression used for periodic counting.
	 *
	 * @return the counter expression
	 */
	public Expression<?> getCounter() { return counter; }

	/**
	 * Sets the counter expression used for periodic counting.
	 *
	 * <p>This should reference a persistent memory location (e.g.,
	 * an element of an {@link ArrayVariable} backed by a
	 * {@code PackedCollection}) so the counter survives across
	 * compiled invocations.</p>
	 *
	 * @param counter the counter expression
	 */
	public void setCounter(Expression<?> counter) { this.counter = counter; }

	/**
	 * Returns the period (number of ticks between executions).
	 *
	 * @return the period
	 */
	public int getPeriod() { return period; }

	/**
	 * Sets the period (number of ticks between executions).
	 *
	 * @param period the number of counter increments before executing
	 *               the child scopes
	 */
	public void setPeriod(int period) { this.period = period; }

	/**
	 * Writes the periodic scope to the specified {@link CodePrintWriter}.
	 *
	 * <p>Generates counter increment, conditional check, child scope execution,
	 * and counter reset. The output structure is:</p>
	 * <pre>{@code
	 * counter = counter + 1;
	 * if (counter >= period) {
	 *     // children
	 *     counter = 0;
	 * }
	 * }</pre>
	 *
	 * @param w the code print writer to write to
	 */
	@Override
	public void write(CodePrintWriter w) {
		w.renderMetadata(getMetadata());
		for (Method m : getMethods()) { w.println(m); }
		for (Statement s : getStatements()) { w.println(s); }
		for (ExpressionAssignment<?> v : getVariables()) { w.println(v); }

		String c = counter.getExpression(w.getLanguage());

		w.println(c + " = " + c + " + 1;");
		w.println("if (" + c + " >= " + period + ") {");
		for (Scope<?> child : getChildren()) { child.write(w); }
		w.println(c + " = 0;");
		w.println("}");

		for (Metric m : getMetrics()) { w.println(m); }
		w.flush();
	}

	/**
	 * Collects arguments for this scope, including dependencies from the
	 * counter expression.
	 *
	 * <p>The counter expression references an {@link ArrayVariable} backed by
	 * persistent memory. This override ensures that the counter's variable
	 * dependencies are included in the function's argument list so that the
	 * compiled code properly declares and passes the counter memory.</p>
	 *
	 * @param <A>    the type to map arguments to
	 * @param mapper function to transform each argument
	 * @return list of mapped arguments with duplicates removed
	 */
	@Override
	protected <A> List<A> arguments(Function<Argument<?>, A> mapper) {
		List<Argument<?>> args = new ArrayList<>();
		args.addAll(extractArgumentDependencies(counter.getDependencies()));
		args.addAll(super.arguments(Function.identity()));
		return Scope.removeDuplicateArguments(args).stream().map(mapper).collect(Collectors.toList());
	}

	@Override
	public Scope<T> simplify(KernelStructureContext context, int depth) {
		PeriodicScope<T> scope = (PeriodicScope<T>) super.simplify(context, depth);
		scope.setCounter(getCounter());
		scope.setPeriod(getPeriod());
		return scope;
	}

	@Override
	public PeriodicScope<T> generate(List<Scope<T>> children) {
		PeriodicScope<T> scope = getMetadata() == null ?
				new PeriodicScope<>(getName()) :
				new PeriodicScope<>(getName(), getMetadata());
		scope.getChildren().addAll(children);
		return scope;
	}
}
