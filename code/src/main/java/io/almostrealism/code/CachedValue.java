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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.lifecycle.SuppliedValue;

import java.util.function.Consumer;

/**
 * A cached wrapper for {@link Evaluable} computations that stores and reuses results.
 *
 * <p>CachedValue extends {@link SuppliedValue} to provide lazy evaluation and caching
 * of expensive computations. Unlike SuppliedValue which uses a {@link java.util.function.Supplier},
 * CachedValue supports parameterized evaluation via the {@link Evaluable} interface,
 * allowing cached computations to receive input arguments.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Lazy evaluation - computation is deferred until first access</li>
 *   <li>Result caching - subsequent calls return the cached result</li>
 *   <li>Argument passing - supports parameterized computation via {@link #evaluate(Object...)}</li>
 *   <li>Null value support - can optionally cache null results</li>
 *   <li>Validation support - inherited from {@link SuppliedValue}</li>
 *   <li>Custom cleanup - supports custom clear behavior for resource management</li>
 * </ul>
 *
 * <h2>Usage with Evaluable</h2>
 * <pre>{@code
 * // Cache an expensive computation
 * CachedValue<Result> cached = new CachedValue<>(args -> {
 *     return expensiveComputation((InputData) args[0]);
 * });
 *
 * // First call computes and caches the result
 * Result r1 = cached.evaluate(inputData);
 *
 * // Subsequent calls return cached result (arguments ignored)
 * Result r2 = cached.evaluate(differentData);  // Returns same as r1
 * }</pre>
 *
 * <h2>Usage with Producer</h2>
 * <pre>{@code
 * // Wrap a Producer for lazy compilation
 * CachedValue<CompiledKernel> cached = new CachedValue<>(producer);
 *
 * // Producer.get() is called lazily on first evaluate
 * CompiledKernel kernel = cached.evaluate();
 * }</pre>
 *
 * <h2>Null Value Handling</h2>
 * <p>By default, a null result is not considered a valid cached value and will
 * trigger re-computation. To allow null as a valid cached result:</p>
 * <pre>{@code
 * CachedValue<Data> cached = new CachedValue<>(args -> maybeNull(args));
 * cached.setAllowNull(true);  // null is now a valid cached value
 * }</pre>
 *
 * <h2>Integration with CacheManager</h2>
 * <p>CachedValue is typically created via {@link CacheManager#get(Evaluable)} which
 * automatically configures access tracking for LRU eviction support.</p>
 *
 * @param <T> the type of value being cached
 * @see CacheManager
 * @see SuppliedValue
 * @see Evaluable
 * @see Producer
 */
public class CachedValue<T> extends SuppliedValue<T> implements Evaluable<T> {
	/**
	 * Optional producer that supplies the evaluable on first access.
	 * Used when CachedValue is constructed with a {@link Producer} instead of
	 * an {@link Evaluable} directly.
	 */
	private Producer<T> source;

	/**
	 * The evaluable that produces values when the cache is empty.
	 * Either set directly via constructor or obtained from {@link #source}.
	 */
	private Evaluable<T> eval;

	/**
	 * Whether null is considered a valid cached value.
	 * When true, a null result from evaluation will be cached and not trigger re-computation.
	 */
	private boolean allowNull;

	/**
	 * Tracks availability when null values are allowed.
	 * This flag is set when evaluation returns null and {@link #allowNull} is true.
	 */
	private boolean available;

	/**
	 * Creates a cached value backed by a {@link Producer}.
	 *
	 * <p>The producer's {@link Producer#get()} method will be called lazily
	 * on first evaluation to obtain the {@link Evaluable}.</p>
	 *
	 * @param source the producer that will supply the evaluable
	 */
	public CachedValue(Producer<T> source) {
		this.source = source;
	}

	/**
	 * Creates a cached value backed by an {@link Evaluable} with no custom cleanup.
	 *
	 * @param source the evaluable that will compute values
	 */
	public CachedValue(Evaluable<T> source) {
		this(source, null);
	}

	/**
	 * Creates a cached value backed by an {@link Evaluable} with custom cleanup behavior.
	 *
	 * @param source the evaluable that will compute values
	 * @param clear  the consumer to invoke when clearing the cached value,
	 *               or {@code null} for default behavior
	 */
	public CachedValue(Evaluable<T> source, Consumer<T> clear) {
		setEvaluable(source);
		setClear(clear);
	}

	/**
	 * Returns whether null is allowed as a valid cached value.
	 *
	 * @return {@code true} if null results are cached, {@code false} otherwise
	 */
	public boolean isAllowNull() {
		return allowNull;
	}

	/**
	 * Sets whether null should be considered a valid cached value.
	 *
	 * <p>When {@code true}, a null result from evaluation will be cached and
	 * subsequent calls to {@link #evaluate(Object...)} will return null without
	 * re-computing. When {@code false} (default), null results are not cached
	 * and evaluation will be attempted again on the next call.</p>
	 *
	 * @param allowNull {@code true} to allow null as a cached value
	 */
	public void setAllowNull(boolean allowNull) {
		this.allowNull = allowNull;
	}

	/**
	 * Checks if a cached value is currently available.
	 *
	 * <p>A value is considered available if either:</p>
	 * <ul>
	 *   <li>The {@link #available} flag is set (for cached null values when
	 *       {@link #allowNull} is true)</li>
	 *   <li>The parent {@link SuppliedValue#isAvailable()} returns true
	 *       (non-null value that passes validation)</li>
	 * </ul>
	 *
	 * @return {@code true} if a cached value is available
	 */
	@Override
	public boolean isAvailable() {
		return available || super.isAvailable();
	}

	/**
	 * Creates a value using an empty argument array.
	 *
	 * <p>This method delegates to {@link #createValue(Object[])} with an
	 * empty array, satisfying the {@link SuppliedValue#createValue()} contract.</p>
	 *
	 * @return the computed value
	 */
	@Override
	protected T createValue() {
		return createValue(new Object[0]);
	}

	/**
	 * Creates a value by invoking the underlying evaluable with the given arguments.
	 *
	 * <p>If this cached value was constructed with a {@link Producer}, the producer's
	 * {@link Producer#get()} method is called lazily on first invocation to obtain
	 * the {@link Evaluable}.</p>
	 *
	 * @param args the arguments to pass to the evaluable
	 * @return the computed value
	 */
	protected T createValue(Object args[]) {
		if (eval == null) eval = source.get();
		return eval.evaluate(args);
	}

	/**
	 * Sets the evaluable that will compute values for this cache.
	 *
	 * <p>This method is package-protected and used internally by {@link CacheManager}
	 * to configure the evaluable with additional access tracking behavior.</p>
	 *
	 * @param eval the evaluable to use for value computation
	 */
	protected void setEvaluable(Evaluable<T> eval) {
		this.eval = eval;
	}

	/**
	 * Evaluates and returns the cached value, computing it if necessary.
	 *
	 * <p>If a cached value is available (per {@link #isAvailable()}), returns it
	 * immediately. Otherwise, computes a new value using the underlying evaluable
	 * with the provided arguments, caches it, and returns it.</p>
	 *
	 * <p><strong>Important:</strong> Arguments are only used for the initial
	 * computation. Once a value is cached, subsequent calls will return the
	 * cached value regardless of the arguments provided.</p>
	 *
	 * <p>If the computed value is null and {@link #isAllowNull()} returns true,
	 * the null value is cached. Otherwise, null results are not cached and
	 * computation will be attempted again on the next call.</p>
	 *
	 * @param args the arguments to pass to the evaluable (only used if not cached)
	 * @return the cached or newly computed value
	 */
	@Override
	public T evaluate(Object... args) {
		if (!isAvailable()) {
			value = createValue(args);
			if (value == null && isAllowNull()) available = true;
		}

		return getValue();
	}
}
