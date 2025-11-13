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

package io.almostrealism.uml;

/**
 * An interface for objects that provide positional value access.
 *
 * <p>This interface represents objects that contain multiple values of type {@code T}
 * accessible by position. It is similar to {@link Multiple} but uses {@code valueAt(pos)}
 * terminology, which is often more appropriate for mathematical objects, coordinates,
 * or value-based structures where "value at position" is more semantically meaningful
 * than "element at index".</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code Plural} is designed for:</p>
 * <ul>
 *   <li><strong>Mathematical Objects:</strong> Vectors, matrices, tensors with component access</li>
 *   <li><strong>Coordinate Systems:</strong> Points, tuples where position represents dimension</li>
 *   <li><strong>Value Sequences:</strong> Time series, function outputs, sampled data</li>
 *   <li><strong>Semantic Clarity:</strong> When "value at position" better describes access pattern</li>
 * </ul>
 *
 * <h2>Comparison with Multiple</h2>
 * <table>
 *   <tr>
 *     <th>Aspect</th>
 *     <th>Plural&lt;T&gt;</th>
 *     <th>Multiple&lt;T&gt;</th>
 *   </tr>
 *   <tr>
 *     <td>Method Name</td>
 *     <td>valueAt(int pos)</td>
 *     <td>get(int index)</td>
 *   </tr>
 *   <tr>
 *     <td>Semantic Use</td>
 *     <td>Mathematical/positional values</td>
 *     <td>Collection-like element access</td>
 *   </tr>
 *   <tr>
 *     <td>Typical Use</td>
 *     <td>Vectors, coordinates, tuples</td>
 *     <td>Lists, arrays, result sets</td>
 *   </tr>
 *   <tr>
 *     <td>Terminology</td>
 *     <td>"position", "component"</td>
 *     <td>"index", "element"</td>
 *   </tr>
 * </table>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Vector implementation:</strong></p>
 * <pre>{@code
 * public class Vector3D implements Plural<Double> {
 *     private final double x, y, z;
 *
 *     public Vector3D(double x, double y, double z) {
 *         this.x = x;
 *         this.y = y;
 *         this.z = z;
 *     }
 *
 *     @Override
 *     public Double valueAt(int pos) {
 *         switch (pos) {
 *             case 0: return x;
 *             case 1: return y;
 *             case 2: return z;
 *             default: throw new IndexOutOfBoundsException(pos);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Accessing vector components:</strong></p>
 * <pre>{@code
 * Vector3D vec = new Vector3D(1.0, 2.0, 3.0);
 * double x = vec.valueAt(0);  // 1.0
 * double y = vec.valueAt(1);  // 2.0
 * double z = vec.valueAt(2);  // 3.0
 * }</pre>
 *
 * <p><strong>Generic tuple:</strong></p>
 * <pre>{@code
 * public class Tuple<T> implements Plural<T> {
 *     private final List<T> values;
 *
 *     @SafeVarargs
 *     public Tuple(T... values) {
 *         this.values = Arrays.asList(values);
 *     }
 *
 *     @Override
 *     public T valueAt(int pos) {
 *         if (pos < 0 || pos >= values.size()) {
 *             throw new IndexOutOfBoundsException(pos);
 *         }
 *         return values.get(pos);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Time series data:</strong></p>
 * <pre>{@code
 * public class TimeSeries implements Plural<Double> {
 *     private final double[] samples;
 *
 *     public TimeSeries(double[] samples) {
 *         this.samples = samples.clone();
 *     }
 *
 *     @Override
 *     public Double valueAt(int pos) {
 *         return samples[pos];
 *     }
 *
 *     public int getLength() {
 *         return samples.length;
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Choosing between Plural and Multiple:</strong></p>
 * <pre>{@code
 * // Use Plural for mathematical/positional values
 * Plural<Double> vector = new Vector3D(1, 2, 3);
 * double component = vector.valueAt(1);  // Get y-component
 *
 * // Use Multiple for collection-like access
 * Multiple<Result> results = executeOperations(ops);
 * Result first = results.get(0);  // Get first result
 * }</pre>
 *
 * @param <T> The type of values at each position
 *
 * @see Multiple
 */
public interface Plural<T> {
	/**
	 * Returns the value at the specified position.
	 *
	 * <p>Implementations should return the value at the given position, where position
	 * typically represents a component index (for vectors/matrices), a coordinate axis,
	 * or a sequential position in a value sequence.</p>
	 *
	 * <p>The behavior for out-of-range positions is implementation-specific, but typically
	 * should throw {@link IndexOutOfBoundsException}.</p>
	 *
	 * <p><strong>Implementation Guidelines:</strong></p>
	 * <ul>
	 *   <li>Use zero-based positioning (0 = first position)</li>
	 *   <li>Throw IndexOutOfBoundsException for invalid positions</li>
	 *   <li>Document the valid position range (e.g., [0, dimensions))</li>
	 *   <li>Consider whether positions map to semantic components (x, y, z, etc.)</li>
	 * </ul>
	 *
	 * @param pos The zero-based position of the value to retrieve
	 * @return The value at the specified position
	 * @throws IndexOutOfBoundsException if the position is out of range (implementation-specific)
	 */
	T valueAt(int pos);
}

