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

package io.almostrealism.uml;

/**
 * An interface for collections or containers that provide indexed access to elements.
 *
 * <p>This interface represents objects that contain multiple elements of type {@code T}
 * and allow retrieval by integer index. It provides a simpler, more focused alternative
 * to {@link java.util.List} for contexts where only indexed read access is needed.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code Multiple} is designed for:</p>
 * <ul>
 *   <li><strong>Simple Indexed Access:</strong> When only get(int) is needed, not full List API</li>
 *   <li><strong>Abstraction:</strong> Hide implementation details while exposing indexed access</li>
 *   <li><strong>Lightweight Contracts:</strong> Minimal interface for indexed data structures</li>
 *   <li><strong>Computation Results:</strong> Batch results, multi-output operations</li>
 * </ul>
 *
 * <h2>Comparison with Related Interfaces</h2>
 * <table>
 *   <tr>
 *     <th>Interface</th>
 *     <th>Purpose</th>
 *     <th>Key Methods</th>
 *   </tr>
 *   <tr>
 *     <td>Multiple&lt;T&gt;</td>
 *     <td>Indexed read access</td>
 *     <td>get(int)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Plural}&lt;T&gt;</td>
 *     <td>Positional value access</td>
 *     <td>valueAt(int)</td>
 *   </tr>
 *   <tr>
 *     <td>List&lt;T&gt;</td>
 *     <td>Full mutable collection</td>
 *     <td>get, set, add, remove, size, ...</td>
 *   </tr>
 * </table>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Basic implementation:</strong></p>
 * <pre>{@code
 * public class ResultSet<T> implements Multiple<T> {
 *     private final List<T> results;
 *
 *     public ResultSet(List<T> results) {
 *         this.results = new ArrayList<>(results);
 *     }
 *
 *     @Override
 *     public T get(int index) {
 *         return results.get(index);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Wrapping arrays:</strong></p>
 * <pre>{@code
 * public class ArrayMultiple<T> implements Multiple<T> {
 *     private final T[] array;
 *
 *     public ArrayMultiple(T[] array) {
 *         this.array = array;
 *     }
 *
 *     @Override
 *     public T get(int index) {
 *         if (index < 0 || index >= array.length) {
 *             throw new IndexOutOfBoundsException(index);
 *         }
 *         return array[index];
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Batch operation results:</strong></p>
 * <pre>{@code
 * public Multiple<PackedCollection<?>> executeBatch(List<Operation> operations) {
 *     List<PackedCollection<?>> results = new ArrayList<>();
 *     for (Operation op : operations) {
 *         results.add(op.execute());
 *     }
 *     return new ResultSet<>(results);
 * }
 *
 * // Usage
 * Multiple<PackedCollection<?>> results = executeBatch(ops);
 * PackedCollection<?> first = results.get(0);
 * PackedCollection<?> second = results.get(1);
 * }</pre>
 *
 * <p><strong>Computed on demand:</strong></p>
 * <pre>{@code
 * public class LazyMultiple<T> implements Multiple<T> {
 *     private final Function<Integer, T> generator;
 *
 *     public LazyMultiple(Function<Integer, T> generator) {
 *         this.generator = generator;
 *     }
 *
 *     @Override
 *     public T get(int index) {
 *         return generator.apply(index);
 *     }
 * }
 * }</pre>
 *
 * @param <T> The type of elements in this collection
 *
 * @see Plural
 */
public interface Multiple<T> {
	/**
	 * Returns the element at the specified index.
	 *
	 * <p>Implementations should return the element at the given zero-based index position.
	 * The behavior for out-of-range indices is implementation-specific, but typically
	 * should throw {@link IndexOutOfBoundsException}.</p>
	 *
	 * <p><strong>Implementation Guidelines:</strong></p>
	 * <ul>
	 *   <li>Use zero-based indexing (0 = first element)</li>
	 *   <li>Throw IndexOutOfBoundsException for invalid indices</li>
	 *   <li>Document the valid index range (e.g., [0, size))</li>
	 *   <li>Consider whether to allow null elements</li>
	 * </ul>
	 *
	 * @param index The zero-based index of the element to retrieve
	 * @return The element at the specified index
	 * @throws IndexOutOfBoundsException if the index is out of range (implementation-specific)
	 */
	T get(int index);
}

