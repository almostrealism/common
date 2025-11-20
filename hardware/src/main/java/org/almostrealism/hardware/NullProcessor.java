/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware;

/**
 * Interface for operations that can handle or replace null results during evaluation.
 *
 * <p>{@link NullProcessor} provides a hook for operations to define custom behavior when
 * evaluation produces a {@code null} result. This is particularly useful for:
 * <ul>
 *   <li><strong>Default values:</strong> Providing fallback results instead of null</li>
 *   <li><strong>Lazy initialization:</strong> Creating results on-demand when null is encountered</li>
 *   <li><strong>Null-safe pipelines:</strong> Ensuring operations can continue despite null intermediate results</li>
 * </ul>
 *
 * <h2>Core Concept: Null Handling Strategy</h2>
 *
 * <p>When an operation evaluates to {@code null}, the framework can call {@link #replaceNull(Object[])}
 * to obtain a replacement value rather than propagating the null:</p>
 *
 * <pre>{@code
 * // Without NullProcessor:
 * T result = operation.evaluate(args);
 * if (result == null) {
 *     throw new NullPointerException();  // Fails
 * }
 *
 * // With NullProcessor:
 * T result = operation.evaluate(args);
 * if (result == null && operation instanceof NullProcessor) {
 *     result = ((NullProcessor<T>) operation).replaceNull(args);  // Recovers
 * }
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Providing Default Values</h3>
 * <pre>{@code
 * public class CachedOperation implements Evaluable<PackedCollection<?>>, NullProcessor<PackedCollection<?>> {
 *     private PackedCollection<?> cache;
 *
 *     @Override
 *     public PackedCollection<?> evaluate(Object... args) {
 *         return cache;  // May be null if not yet cached
 *     }
 *
 *     @Override
 *     public PackedCollection<?> replaceNull(Object[] args) {
 *         // Create default value when cache is null
 *         cache = new PackedCollection<>(1000);
 *         compute(cache, args);
 *         return cache;
 *     }
 * }
 * }</pre>
 *
 * <h3>Lazy Initialization</h3>
 * <pre>{@code
 * public class LazyProducer implements Producer<Vector>, NullProcessor<Vector> {
 *     private Vector result;
 *
 *     @Override
 *     public Vector evaluate(Object... args) {
 *         return result;  // null until first invocation
 *     }
 *
 *     @Override
 *     public Vector replaceNull(Object[] args) {
 *         // Initialize on first null encounter
 *         result = new Vector();
 *         result.setX((Double) args[0]);
 *         result.setY((Double) args[1]);
 *         result.setZ((Double) args[2]);
 *         return result;
 *     }
 * }
 * }</pre>
 *
 * <h3>Conditional Computation</h3>
 * <pre>{@code
 * public class ConditionalOperation implements Evaluable<PackedCollection<?>>, NullProcessor<PackedCollection<?>> {
 *     @Override
 *     public PackedCollection<?> evaluate(Object... args) {
 *         boolean condition = checkCondition(args);
 *         if (condition) {
 *             return computeResult(args);
 *         }
 *         return null;  // Indicates condition not met
 *     }
 *
 *     @Override
 *     public PackedCollection<?> replaceNull(Object[] args) {
 *         // Provide alternative when condition fails
 *         return new PackedCollection<>(1000).fill(0.0);  // Empty result
 *     }
 * }
 * }</pre>
 *
 * <h2>Integration with DestinationEvaluable</h2>
 *
 * <p>{@link DestinationEvaluable} uses {@link NullProcessor} during element-wise batch processing:</p>
 * <pre>{@code
 * // In DestinationEvaluable.evaluate():
 * for (int i = 0; i < destination.getCount(); i++) {
 *     T result = operation.evaluate(extractElement(args, i));
 *
 *     if (result == null && operation instanceof NullProcessor) {
 *         result = ((NullProcessor<T>) operation).replaceNull(extractElement(args, i));
 *     }
 *
 *     destination.set(i, result);
 * }
 * }</pre>
 *
 * <h2>Default Behavior</h2>
 *
 * <p>The default implementation throws {@link NullPointerException}, maintaining fail-fast behavior
 * unless explicitly overridden:</p>
 * <pre>{@code
 * public interface NullProcessor<T> {
 *     default T replaceNull(Object[] args) {
 *         throw new NullPointerException();  // Fail unless overridden
 *     }
 * }
 * }</pre>
 *
 * <h2>When to Implement</h2>
 *
 * <p>Consider implementing {@link NullProcessor} when:
 * <ul>
 *   <li>Operations may legitimately return null under normal conditions</li>
 *   <li>Null represents "no result" rather than an error</li>
 *   <li>You want to provide graceful fallback behavior</li>
 *   <li>Lazy initialization is needed</li>
 * </ul>
 *
 * <h2>When NOT to Implement</h2>
 *
 * <p>Avoid implementing {@link NullProcessor} when:
 * <ul>
 *   <li>Null should indicate an error (let the NullPointerException occur)</li>
 *   <li>Operations should always produce valid results</li>
 *   <li>Masking null results would hide bugs</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be thread-safe if {@link #replaceNull(Object[])} modifies shared state
 * (e.g., initializing a shared cache). The default implementation is stateless and thread-safe.</p>
 *
 * @param <T> The type of result that can be produced to replace null
 *
 * @see DestinationEvaluable
 * @see io.almostrealism.relation.Evaluable
 */
public interface NullProcessor<T> {
	/**
	 * Provides a replacement value when evaluation produces {@code null}.
	 *
	 * <p>This method is called when an operation returns {@code null} during evaluation,
	 * giving the operation a chance to provide an alternative result instead of propagating
	 * the null.</p>
	 *
	 * <h3>Arguments</h3>
	 * <p>The {@code args} array contains the same arguments that were passed to the evaluation
	 * that produced {@code null}. This allows the replacement logic to depend on the inputs.</p>
	 *
	 * <h3>Default Behavior</h3>
	 * <p>The default implementation throws {@link NullPointerException}, maintaining fail-fast
	 * behavior. Override this method to provide custom null-handling logic.</p>
	 *
	 * @param args The arguments that were passed to the evaluation that returned null
	 * @return A non-null replacement value
	 * @throws NullPointerException If no replacement value can be provided (default behavior)
	 */
	default T replaceNull(Object args[]) {
		throw new NullPointerException();
	}
}
