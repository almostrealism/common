/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.util;

import io.almostrealism.uml.Plural;
import io.almostrealism.uml.Signature;

import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * A generic interface representing an ordered sequence of values that can be accessed by position.
 *
 * <p>{@code Sequence} combines positional value access from {@link Plural} with identity tracking
 * from {@link Signature}, providing a foundation for sequences that may contain millions of elements
 * while supporting both random access and streaming operations.
 *
 * <h2>Key Capabilities</h2>
 * <ul>
 *   <li><b>Positional Access</b>: Access values by both {@code int} and {@code long} positions
 *       via {@link #valueAt(int)} and {@link #valueAt(long)}</li>
 *   <li><b>Numeric Convenience</b>: Direct access to numeric values via {@link #intAt(int)}
 *       and {@link #doubleAt(int)} for sequences of {@link Number}</li>
 *   <li><b>Streaming</b>: Convert to {@link Stream} via {@link #stream()} for functional operations</li>
 *   <li><b>Array Conversion</b>: Convert to array via {@link #toArray()}</li>
 *   <li><b>Type Information</b>: Query element type via {@link #getType()}</li>
 *   <li><b>Identity</b>: Generate unique signatures for caching and deduplication (inherited from {@link Signature})</li>
 * </ul>
 *
 * <h2>Long Position Support</h2>
 * <p>Unlike standard Java collections, {@code Sequence} supports sequences with more than
 * {@link Integer#MAX_VALUE} elements through {@link #lengthLong()} and {@link #valueAt(long)}.
 * This is essential for kernel computations that may operate on very large datasets.</p>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link ArrayItem}: General-purpose array-backed sequence with modulus optimization</li>
 *   <li>{@link io.almostrealism.kernel.IndexSequence}: Specialized sequence for numeric index values</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Create a sequence from an array
 * Sequence<Integer> seq = new ArrayItem<>(new Integer[]{1, 2, 3, 4, 5}, Integer[]::new);
 *
 * // Access values by position
 * Integer first = seq.valueAt(0);
 * Integer third = seq.valueAt(2);
 *
 * // Stream the values
 * seq.stream().filter(x -> x > 2).forEach(System.out::println);
 *
 * // Get distinct values
 * Integer[] unique = seq.distinct();
 *
 * // Convert to array
 * Integer[] array = seq.toArray();
 * }</pre>
 *
 * @param <T> the type of elements in this sequence
 *
 * @author Michael Murray
 * @see Plural
 * @see Signature
 * @see ArrayItem
 * @see io.almostrealism.kernel.IndexSequence
 */
public interface Sequence<T> extends Plural<T>, Signature {

	/**
	 * {@inheritDoc}
	 *
	 * <p>This default implementation delegates to {@link #valueAt(long)} by casting
	 * the {@code int} position to {@code long}.
	 *
	 * @param pos the zero-based position of the value to retrieve
	 * @return the value at the specified position
	 */
	@Override
	default T valueAt(int pos) { return valueAt((long) pos); }

	/**
	 * Returns the value at the specified position using {@code long} indexing.
	 *
	 * <p>This method supports sequences with more than {@link Integer#MAX_VALUE} elements.
	 * Implementations may apply modular arithmetic if the sequence has a repeating pattern
	 * shorter than the total length.
	 *
	 * @param pos the zero-based position of the value to retrieve (may exceed {@code Integer.MAX_VALUE})
	 * @return the value at the specified position
	 * @throws UnsupportedOperationException if the computed position exceeds {@code Integer.MAX_VALUE}
	 *         and the implementation cannot handle it
	 */
	T valueAt(long pos);

	/**
	 * Returns the integer value at the specified position.
	 *
	 * <p>This convenience method retrieves the value at the given position and converts
	 * it to an {@code int}. The value must be a {@link Number} or a {@link ClassCastException}
	 * will be thrown.
	 *
	 * @param pos the zero-based position of the value to retrieve
	 * @return the value at the specified position as an {@code int}
	 * @throws ClassCastException if the value is not a {@link Number}
	 */
	default int intAt(int pos) { return ((Number) valueAt(pos)).intValue(); }

	/**
	 * Returns the double value at the specified position.
	 *
	 * <p>This convenience method retrieves the value at the given position and converts
	 * it to a {@code double}. The value must be a {@link Number} or a {@link ClassCastException}
	 * will be thrown.
	 *
	 * @param pos the zero-based position of the value to retrieve
	 * @return the value at the specified position as a {@code double}
	 * @throws ClassCastException if the value is not a {@link Number}
	 */
	default double doubleAt(int pos) { return ((Number) valueAt(pos)).doubleValue(); }

	/**
	 * Returns an array containing the distinct values in this sequence.
	 *
	 * <p>The returned array contains each unique value that appears in this sequence,
	 * without duplicates. The order of elements in the result is implementation-dependent
	 * but typically preserves the order of first occurrence.
	 *
	 * @return an array of distinct values from this sequence
	 */
	T[] distinct();

	/**
	 * Returns a sequential {@link Stream} of all values in this sequence.
	 *
	 * <p>This method creates a stream that iterates through all positions from 0 to
	 * {@link #lengthLong()} - 1, retrieving each value via {@link #valueAt(long)}.
	 *
	 * <p><b>Note:</b> For sequences with a repeating pattern, consider using {@link #values()}
	 * to stream only the unique values within one period.
	 *
	 * @return a stream of all values in this sequence
	 */
	default Stream<T> stream() {
		return LongStream.range(0, lengthLong()).mapToObj(this::valueAt);
	}

	/**
	 * Returns a {@link Stream} of the underlying values in this sequence.
	 *
	 * <p>For sequences with repeating patterns (modular sequences), this method may return
	 * only the values within one period, avoiding redundant elements. The default
	 * implementation delegates to {@link #stream()}.
	 *
	 * <p>Implementations may override this method to provide more efficient streaming
	 * of distinct values or to avoid materializing the entire sequence.
	 *
	 * @return a stream of values in this sequence (potentially without repetition)
	 */
	default Stream<T> values() {
		return stream();
	}

	/**
	 * Returns the length of this sequence as an {@code int}.
	 *
	 * <p>This method converts the {@code long} length to {@code int}, throwing
	 * {@link ArithmeticException} if the length exceeds {@link Integer#MAX_VALUE}.
	 *
	 * @return the number of elements in this sequence
	 * @throws ArithmeticException if the sequence length exceeds {@code Integer.MAX_VALUE}
	 * @see #lengthLong()
	 */
	default int length()  { return Math.toIntExact(lengthLong()); }

	/**
	 * Returns the length of this sequence as a {@code long}.
	 *
	 * <p>This method supports sequences with more than {@link Integer#MAX_VALUE} elements.
	 * This is the logical length of the sequence, which may be greater than the number
	 * of stored values if the sequence uses modular arithmetic for repeating patterns.
	 *
	 * @return the number of elements in this sequence
	 */
	long lengthLong();

	/**
	 * Returns an array containing all values in this sequence.
	 *
	 * <p>The returned array has length equal to {@link #length()}, with each element
	 * equal to the value at the corresponding position. This method may materialize
	 * the entire sequence into memory.
	 *
	 * @return an array containing all values in this sequence
	 * @throws ArithmeticException if the sequence length exceeds {@code Integer.MAX_VALUE}
	 */
	T[] toArray();

	/**
	 * Returns the runtime type of elements in this sequence.
	 *
	 * <p>This method returns the {@link Class} object representing the type of elements
	 * stored in this sequence. For sequences containing multiple types, the behavior
	 * is implementation-dependent but should return a common supertype.
	 *
	 * @return the class representing the element type
	 * @throws RuntimeException if the sequence contains elements of incompatible types
	 */
	Class<? extends T> getType();
}
