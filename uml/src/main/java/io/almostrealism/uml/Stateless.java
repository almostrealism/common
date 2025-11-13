/*
 * Copyright 2016 Michael Murray
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

package io.almostrealism.uml;

import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Target;

/**
 * A marker annotation for types that contain no meaningful mutable state.
 *
 * <p>This annotation identifies types that are stateless or effectively immutable, meaning
 * they don't maintain state that varies between method invocations. Stateless types are
 * ideal for pure functions, utility classes, and mathematical operations where behavior
 * depends only on input parameters, not internal state.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code @Stateless} is used to mark types that:</p>
 * <ul>
 *   <li><strong>Have No Mutable State:</strong> No instance variables that change over time</li>
 *   <li><strong>Pure Functionality:</strong> Behavior depends only on input parameters</li>
 *   <li><strong>Thread-Safe by Nature:</strong> Safe to use concurrently without synchronization</li>
 *   <li><strong>Predictable Behavior:</strong> Same inputs always produce same outputs</li>
 * </ul>
 *
 * <h2>Characteristics of Stateless Types</h2>
 * <p>Types marked with {@code @Stateless} typically exhibit:</p>
 * <ul>
 *   <li><strong>No Instance State:</strong> Either no instance variables, or only immutable fields</li>
 *   <li><strong>Deterministic:</strong> Output depends solely on input parameters</li>
 *   <li><strong>Side-Effect Free:</strong> Don't modify external state (ideally)</li>
 *   <li><strong>Reentrant:</strong> Multiple threads can safely call methods simultaneously</li>
 *   <li><strong>No Memory Between Calls:</strong> Each invocation is independent</li>
 * </ul>
 *
 * <h2>Common Use Cases</h2>
 *
 * <p><strong>Utility classes with static methods:</strong></p>
 * <pre>{@code
 * @Stateless
 * public class MathUtils {
 *     // Pure mathematical functions
 *     public static double sigmoid(double x) {
 *         return 1.0 / (1.0 + Math.exp(-x));
 *     }
 *
 *     public static double[] softmax(double[] values) {
 *         double max = Arrays.stream(values).max().orElse(0);
 *         double sum = Arrays.stream(values).map(v -> Math.exp(v - max)).sum();
 *         return Arrays.stream(values).map(v -> Math.exp(v - max) / sum).toArray();
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Pure computational functions:</strong></p>
 * <pre>{@code
 * @Stateless
 * @Function
 * public class DotProduct implements Evaluable<Double> {
 *     // No instance state - only configuration
 *     private final int dimensions;
 *
 *     public DotProduct(int dimensions) {
 *         this.dimensions = dimensions;  // Immutable configuration
 *     }
 *
 *     @Override
 *     public Double evaluate(PackedCollection<?> a, PackedCollection<?> b) {
 *         // Pure computation - no state modified
 *         double sum = 0;
 *         for (int i = 0; i < dimensions; i++) {
 *             sum += a.valueAt(i) * b.valueAt(i);
 *         }
 *         return sum;
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Immutable transformations:</strong></p>
 * <pre>{@code
 * @Stateless
 * public class VectorNormalizer {
 *     // Produces new results without modifying state
 *     public PackedCollection<?> normalize(PackedCollection<?> input) {
 *         double magnitude = computeMagnitude(input);
 *         return input.divide(magnitude);  // Returns new collection
 *     }
 *
 *     private double computeMagnitude(PackedCollection<?> v) {
 *         // Pure helper method
 *         return Math.sqrt(v.stream().map(x -> x * x).sum());
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Factories with no state:</strong></p>
 * <pre>{@code
 * @Stateless
 * public class OperationFactory {
 *     // Creates objects but maintains no state
 *     public Producer<PackedCollection<?>> createAddition(
 *             Producer<PackedCollection<?>> a,
 *             Producer<PackedCollection<?>> b) {
 *         return new Add(a, b);
 *     }
 *
 *     public Producer<PackedCollection<?>> createMultiplication(
 *             Producer<PackedCollection<?>> a,
 *             Producer<PackedCollection<?>> b) {
 *         return new Multiply(a, b);
 *     }
 * }
 * }</pre>
 *
 * <h2>Stateless vs Other Annotations</h2>
 * <table>
 *   <tr>
 *     <th>Annotation</th>
 *     <th>State</th>
 *     <th>Use Case</th>
 *     <th>Thread Safety</th>
 *   </tr>
 *   <tr>
 *     <td>@Stateless</td>
 *     <td>None or immutable</td>
 *     <td>Utilities, pure functions</td>
 *     <td>Inherently thread-safe</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Function @Function}</td>
 *     <td>Configuration only</td>
 *     <td>Parameterized computations</td>
 *     <td>Depends on implementation</td>
 *   </tr>
 *   <tr>
 *     <td>{@link ModelEntity @ModelEntity}</td>
 *     <td>Significant, mutable</td>
 *     <td>Domain models, trained networks</td>
 *     <td>Often requires synchronization</td>
 *   </tr>
 * </table>
 *
 * <h2>Stateless + Function Combination</h2>
 * <p>Types can be both {@code @Stateless} and {@code @Function}, indicating pure
 * computational functions:</p>
 * <pre>{@code
 * @Stateless
 * @Function
 * public class PureOperation implements Evaluable<Double> {
 *     // Immutable configuration
 *     private final double coefficient;
 *
 *     // Pure evaluation - no state changes
 *     public Double evaluate(Double input) {
 *         return input * coefficient;
 *     }
 * }
 * }</pre>
 *
 * <h2>Framework Integration</h2>
 * <p>In the Almost Realism framework, {@code @Stateless} types:</p>
 * <ul>
 *   <li><strong>Optimization Targets:</strong> Eligible for aggressive optimization</li>
 *   <li><strong>Parallelization:</strong> Safe to execute in parallel without locks</li>
 *   <li><strong>Caching:</strong> Results can be cached based purely on inputs</li>
 *   <li><strong>Reusability:</strong> Single instance can be shared across contexts</li>
 *   <li><strong>Graph Fusion:</strong> Stateless operations can be fused/merged safely</li>
 * </ul>
 *
 * <h2>Design Considerations</h2>
 * <p>When designing {@code @Stateless} types:</p>
 * <ul>
 *   <li><strong>Immutable Fields Only:</strong> Any instance variables should be final and immutable</li>
 *   <li><strong>No Side Effects:</strong> Avoid modifying global state or external resources</li>
 *   <li><strong>Pure Methods:</strong> Method results should depend only on parameters</li>
 *   <li><strong>Thread Safety:</strong> Verify that immutable state is truly immutable</li>
 *   <li><strong>Static Methods:</strong> Consider using static methods for true stateless utilities</li>
 * </ul>
 *
 * <h2>Benefits of Stateless Design</h2>
 * <ul>
 *   <li><strong>Thread Safety:</strong> No synchronization needed for concurrent access</li>
 *   <li><strong>Testability:</strong> Easy to test - no setup/teardown of state</li>
 *   <li><strong>Predictability:</strong> Behavior is deterministic and easy to reason about</li>
 *   <li><strong>Reusability:</strong> Safe to reuse instances across different contexts</li>
 *   <li><strong>Performance:</strong> Eligible for optimization and caching</li>
 *   <li><strong>Maintainability:</strong> Less complexity from state management</li>
 * </ul>
 *
 * <h2>Best Practices</h2>
 * <ul>
 *   <li>Mark types with no mutable state as {@code @Stateless}</li>
 *   <li>Combine with {@code @Function} for pure computational functions</li>
 *   <li>Make all fields {@code final} in stateless types</li>
 *   <li>Avoid modifying parameters or external state</li>
 *   <li>Document that the type is thread-safe due to statelessness</li>
 *   <li>Consider making constructors private and providing static factory methods</li>
 * </ul>
 *
 * <h2>Annotation Properties</h2>
 * <p>This is a marker annotation with no attributes. Its presence conveys that the type
 * has no meaningful mutable state. It applies to types (classes, interfaces) via
 * {@code @Target(TYPE)}.</p>
 *
 * @see Function
 * @see ModelEntity
 * @author Michael Murray
 */
@Target(TYPE)
public @interface Stateless {
}
