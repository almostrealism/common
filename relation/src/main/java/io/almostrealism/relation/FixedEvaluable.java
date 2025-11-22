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

package io.almostrealism.relation;

import java.util.function.Supplier;

/**
 * An {@link Evaluable} that always returns the same value regardless of arguments.
 *
 * <p>{@link FixedEvaluable} represents a computation whose result is predetermined
 * and does not depend on runtime inputs. This is useful for representing constants,
 * pre-computed values, or cached results in the computation framework.</p>
 *
 * <h2>Behavior</h2>
 * <p>The {@link #evaluate(Object...)} method ignores all provided arguments and
 * returns the result of {@link #get()}. This makes {@link FixedEvaluable} suitable
 * as both an {@link Evaluable} and a {@link Supplier}.</p>
 *
 * <h2>Common Implementations</h2>
 * <ul>
 *   <li>{@link Provider} - Wraps a constant value</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a fixed evaluable for a constant
 * FixedEvaluable<Double> pi = () -> Math.PI;
 *
 * // All evaluations return the same value
 * double v1 = pi.evaluate();
 * double v2 = pi.evaluate("ignored", 42);
 * assert v1 == v2;
 * }</pre>
 *
 * @param <T> the type of the fixed result value
 *
 * @see Evaluable
 * @see Provider
 *
 * @author Michael Murray
 */
public interface FixedEvaluable<T> extends Evaluable<T>, Supplier<T> {
	/**
	 * Evaluates this fixed computation, ignoring all arguments.
	 *
	 * <p>This implementation delegates to {@link #get()}, making the
	 * provided arguments irrelevant to the result.</p>
	 *
	 * @param args arguments (ignored)
	 * @return the fixed result value
	 */
	@Override
	default T evaluate(Object... args) { return get(); }
}
