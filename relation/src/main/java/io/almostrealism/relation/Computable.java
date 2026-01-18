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

/**
 * A marker interface for types that represent computational activities requiring
 * computing resources.
 *
 * <p>{@link Computable} distinguishes types that <em>perform</em> computation from
 * types that are merely <em>operated upon</em> by computation. This distinction
 * is fundamental to the Almost Realism framework:</p>
 *
 * <ul>
 *   <li><b>Computable types:</b> {@link Producer}, {@link Evaluable}, operations</li>
 *   <li><b>Non-computable types:</b> Data containers, tensors, collections</li>
 * </ul>
 *
 * <h2>Purpose</h2>
 * <p>The {@link Computable} interface serves as a tagging mechanism that enables:</p>
 * <ul>
 *   <li>Type-safe handling of computation vs. data</li>
 *   <li>Identification of constant (compile-time known) computations</li>
 *   <li>Framework-level optimizations based on computation characteristics</li>
 * </ul>
 *
 * <h2>Constant Computations</h2>
 * <p>A computation is considered <em>constant</em> if its result is known at
 * compile time and does not depend on runtime inputs. Constant computations
 * can be evaluated once and their results inlined, avoiding repeated computation.</p>
 *
 * @see Producer
 * @see Evaluable
 *
 * @author Michael Murray
 */
public interface Computable {
	/**
	 * Returns {@code true} if this computation produces a constant result.
	 *
	 * <p>A constant computation is one whose result is independent of runtime
	 * inputs and can be computed at compile time. Examples include:</p>
	 * <ul>
	 *   <li>Literal values wrapped in computation</li>
	 *   <li>Pure computations on other constants</li>
	 *   <li>Pre-computed lookup tables</li>
	 * </ul>
	 *
	 * <p>Identifying constant computations enables optimizations such as
	 * constant folding and compile-time evaluation.</p>
	 *
	 * @return {@code true} if this computation is constant, {@code false} otherwise
	 */
	default boolean isConstant() {
		return false;
	}

	default boolean isProvider() { return false; }

	static <T> boolean constant(T c) {
		if (c instanceof Computable) {
			return ((Computable) c).isConstant();
		}

		return false;
	}

	static <T> boolean provider(T c) {
		if (c instanceof Computable) {
			return ((Computable) c).isProvider();
		}

		return false;
	}
}
