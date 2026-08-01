/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Scope;

import java.util.Collection;
import java.util.Collections;

/**
 * An {@link Operator} that always produces a fixed constant value.
 *
 * <p>{@code Constant} wraps a single Java value and exposes it as an {@link Operator}
 * with no children and an empty {@link Scope}. The returned {@link io.almostrealism.relation.Evaluable}
 * ignores all arguments and returns the wrapped value directly.</p>
 *
 * @param <T> the type of the constant value
 *
 * @see Operator
 * @see io.almostrealism.expression.Constant
 */
public class Constant<T> implements Operator<T> {
	/** The constant value that this operator always returns. */
	private T v;

	/**
	 * Creates a constant operator that always returns the given value.
	 *
	 * @param v the constant value
	 */
	public Constant(T v) { this.v = v; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns an evaluable that ignores all arguments and returns the constant value.
	 *
	 * @return a constant evaluable
	 */
	@Override
	public Evaluable<T> get() {
		return args -> v;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>A constant has no child processes.
	 *
	 * @return an empty collection
	 */
	@Override
	public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns an empty scope (the constant value is not yet properly represented in the scope model).
	 *
	 * @param context the kernel structure context
	 * @return an empty scope
	 */
	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		Scope<T> s = new Scope<>();
		// TODO  This is not correct
		// s.getVariables().add(new Variable(v.getClass().getSimpleName(), v));
		return s;
	}
}
