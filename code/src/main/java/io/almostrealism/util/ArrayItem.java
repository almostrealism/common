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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * An array-backed implementation of {@link Sequence} that supports modular repetition
 * and constant-value optimization.
 *
 * <p>{@code ArrayItem} provides an efficient representation for sequences that may have:
 * <ul>
 *   <li><b>Repeating patterns</b>: Sequences where values repeat after a certain period (modulus)</li>
 *   <li><b>Constant values</b>: Sequences where all positions contain the same value</li>
 *   <li><b>Long logical lengths</b>: Sequences with many more logical positions than stored values</li>
 * </ul>
 *
 * <h2>Storage Optimization</h2>
 * <p>The class optimizes storage based on the sequence content:</p>
 * <ul>
 *   <li>If all values are identical, only a single value is stored ({@code single} field)</li>
 *   <li>If values repeat, only one period is stored with a modulus ({@code mod} field)</li>
 *   <li>Position lookups use modular arithmetic: {@code values[pos % mod]}</li>
 * </ul>
 *
 * <h2>Modulus Calculation</h2>
 * <p>The modulus represents the period after which values repeat. For example:</p>
 * <pre>{@code
 * values = [A, B, C, A, B, C, A, B, C]  ->  mod = 3, stored = [A, B, C]
 * values = [X, X, X, X, X]              ->  mod = 1, single = X
 * values = [1, 2, 3, 4]                 ->  mod = 4, stored = [1, 2, 3, 4]
 * }</pre>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Create a sequence from an array
 * ArrayItem<Integer> seq = new ArrayItem<>(new Integer[]{1, 2, 3}, Integer[]::new);
 *
 * // Create a constant sequence (single value repeated)
 * ArrayItem<String> constant = new ArrayItem<>("value", 1000, String[]::new);
 *
 * // Access values (uses modular arithmetic internally)
 * Integer val = seq.valueAt(5);  // Returns values[5 % 3] = values[2] = 3
 *
 * // Get distinct values
 * Integer[] unique = seq.distinct();  // [1, 2, 3]
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is not thread-safe. If multiple threads access an {@code ArrayItem}
 * concurrently and at least one modifies it, external synchronization is required.</p>
 *
 * @param <T> the type of elements in this sequence
 *
 * @author Michael Murray
 * @see Sequence
 * @see io.almostrealism.kernel.ArrayIndexSequence
 */
public class ArrayItem<T> implements Sequence<T> {

	/**
	 * Global flag to enable automatic modulus calculation for repeating patterns.
	 *
	 * <p>When {@code true}, the constructor will analyze the input array to detect
	 * repeating patterns and compute the optimal modulus. This analysis has O(n)
	 * complexity and may be expensive for large arrays.
	 *
	 * <p>When {@code false} (default), the modulus is set to the array length unless
	 * all values are identical (in which case mod = 1).
	 *
	 * @see #calculateMod(Object[])
	 */
	public static boolean enableCalculateMod = false;

	/**
	 * The array of stored values, or {@code null} if this is a constant sequence.
	 *
	 * <p>For non-constant sequences, this array stores one complete period of values.
	 * The logical sequence is formed by repeating this array as needed.
	 */
	private T[] values;

	/**
	 * The single constant value for constant sequences, or {@code null} for non-constant sequences.
	 *
	 * <p>When all positions in the sequence contain the same value, only this single
	 * value is stored instead of an array. In this case, {@link #values} is {@code null}
	 * and {@link #mod} is 1.
	 */
	private T single;

	/**
	 * The modulus (period length) of this sequence.
	 *
	 * <p>Position lookups use modular arithmetic: the value at position {@code pos}
	 * is retrieved from index {@code pos % mod}. For constant sequences, mod = 1.
	 */
	private int mod;

	/**
	 * The logical length of this sequence.
	 *
	 * <p>This represents the total number of logical positions, which may be greater
	 * than the number of stored values if the sequence repeats.
	 */
	private long len;

	/**
	 * The element type of this sequence, used for array creation.
	 *
	 * <p>This may be {@code null} if not explicitly provided; in that case, it will
	 * be determined lazily from the first element when {@link #getType()} is called.
	 */
	protected Class<T> type;

	/**
	 * Factory function for creating arrays of type {@code T[]}.
	 *
	 * <p>This is used by methods that need to create new arrays, such as
	 * {@link #toArray()}, {@link #distinct()}, and {@link #apply(Function, IntFunction)}.
	 */
	private IntFunction<T[]> generator;

	/**
	 * Creates a new {@code ArrayItem} from the given array with automatic type detection.
	 *
	 * <p>This is a convenience constructor that delegates to
	 * {@link #ArrayItem(Class, Object[], IntFunction)} with a {@code null} type.
	 * The element type will be determined lazily when {@link #getType()} is called.
	 *
	 * @param values    the array of values (must not be empty)
	 * @param generator a function to create arrays of the element type (e.g., {@code String[]::new})
	 * @throws IllegalArgumentException if {@code values} is empty
	 */
	public ArrayItem(T[] values, IntFunction<T[]> generator) {
		this(null, values, generator);
	}

	/**
	 * Creates a new {@code ArrayItem} from the given array with the specified element type.
	 *
	 * <p>This constructor sets the logical length to the array length.
	 *
	 * @param type      the element type, or {@code null} for lazy detection
	 * @param values    the array of values (must not be empty)
	 * @param generator a function to create arrays of the element type
	 * @throws IllegalArgumentException if {@code values} is empty
	 */
	public ArrayItem(Class<T> type, T[] values, IntFunction<T[]> generator) {
		this(type, values, values.length, generator);
	}

	/**
	 * Creates a new {@code ArrayItem} from the given array with a specified logical length.
	 *
	 * <p>This is the primary constructor that initializes all fields. It performs the
	 * following optimizations:
	 * <ul>
	 *   <li>If all values are equal, stores only a single value with mod = 1</li>
	 *   <li>If {@link #enableCalculateMod} is {@code true} and the array length equals
	 *       the logical length, calculates the optimal modulus for repeating patterns</li>
	 *   <li>Otherwise, sets the modulus to the array length</li>
	 * </ul>
	 *
	 * <p>The logical length may be greater than the array length to represent sequences
	 * that logically repeat beyond the stored values.
	 *
	 * @param type      the element type, or {@code null} for lazy detection
	 * @param values    the array of values (must not be empty)
	 * @param len       the logical length of the sequence (must be at least 1)
	 * @param generator a function to create arrays of the element type
	 * @throws IllegalArgumentException if {@code values} is empty or {@code len} is less than 1
	 */
	public ArrayItem(Class<T> type, T[] values, long len, IntFunction<T[]> generator) {
		this.type = type;
		this.len = len;

		if (values.length < 1 || len < 1) {
			throw new IllegalArgumentException();
		} else if (values.length == 1 || !Stream.of(values).anyMatch(v -> !Objects.equals(v, values[0]))) {
			this.single = values[0];
			this.mod = 1;
		} else if (enableCalculateMod && len < Integer.MAX_VALUE && values.length == len) {
			this.values = values;
			this.mod = calculateMod(values);
		} else {
			this.values = values;
			this.mod = values.length;
		}

		this.generator = generator;
	}

	/**
	 * Creates a constant sequence where all positions contain the same value.
	 *
	 * <p>This constructor is optimized for constant sequences, storing only the single
	 * value with mod = 1. The element type is inferred from the value's class.
	 *
	 * <p>Example:
	 * <pre>{@code
	 * // Create a sequence of 1000 zeros
	 * ArrayItem<Integer> zeros = new ArrayItem<>(0, 1000, Integer[]::new);
	 * }</pre>
	 *
	 * @param value     the constant value for all positions
	 * @param len       the logical length of the sequence
	 * @param generator a function to create arrays of the element type
	 */
	@SuppressWarnings("unchecked")
	public ArrayItem(T value, long len, IntFunction<T[]> generator) {
		this.mod = 1;
		this.len = len;
		this.single = value;
		this.type = (Class<T>) value.getClass();
		this.generator = generator;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation uses modular arithmetic to map the position to the stored
	 * array index. For constant sequences (where {@link #single} is set), returns the
	 * single value regardless of position.
	 *
	 * @param pos the position to retrieve (automatically reduced modulo {@link #mod})
	 * @return the value at the specified position
	 * @throws UnsupportedOperationException if the position modulo {@code mod} exceeds
	 *         {@code Integer.MAX_VALUE}
	 */
	@Override
	public T valueAt(long pos) {
		pos = pos % mod;

		if (pos > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException();
		}

		return values == null ? single : values[(int) pos];
	}

	/**
	 * Applies a transformation function to the underlying values and returns a new array.
	 *
	 * <p>For non-constant sequences, transforms each value in the stored array.
	 * For constant sequences, applies the transformation to the single value and returns
	 * a single-element array.
	 *
	 * <p>This method operates on the stored values only, not on all logical positions.
	 * It is primarily used by subclasses to implement map operations.
	 *
	 * @param <V>       the type of the transformed values
	 * @param f         the transformation function to apply to each value
	 * @param generator a function to create arrays of the result type
	 * @return an array containing the transformed values
	 */
	protected <V> V[] apply(Function<T, V> f, IntFunction<V[]> generator) {
		if (single == null) {
			return Stream.of(values).map(f).toArray(generator);
		} else {
			return IntStream.range(0, 1).mapToObj(i -> f.apply(single)).toArray(generator);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation streams the underlying values and removes duplicates.
	 * The result preserves the order of first occurrence.
	 *
	 * @return an array of distinct values from this sequence
	 */
	@Override
	public T[] distinct() {
		return values().distinct().toArray(generator);
	}

	/**
	 * Returns the single constant value if this is a constant sequence.
	 *
	 * <p>This method is primarily for use by subclasses to check whether the sequence
	 * is constant and to access the constant value directly.
	 *
	 * @return the single constant value, or {@code null} if this is not a constant sequence
	 */
	protected T single() { return single; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation returns a stream of the underlying stored values, not all
	 * logical positions. For constant sequences, returns a single-element stream.
	 * For non-constant sequences, returns a stream of the stored array.
	 *
	 * <p>This is more efficient than {@link #stream()} for operations that only need
	 * to process each distinct value once, such as computing statistics or checking
	 * for the presence of specific values.
	 *
	 * @return a stream of the stored values (one period of the sequence)
	 */
	@Override
	public Stream<T> values() {
		return values == null ? Stream.of(single) : Stream.of(values);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation lazily determines the element type if not explicitly provided
	 * at construction time. For constant sequences, uses the single value's class. For
	 * non-constant sequences, checks that all elements have the same type and returns it.
	 *
	 * @return the runtime class of elements in this sequence
	 * @throws RuntimeException if the sequence contains elements of multiple incompatible types
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Class<? extends T> getType() {
		if (type == null) {
			if (single != null) {
				type = (Class<T>) single.getClass();
			} else {
				List<Class<?>> types = stream().map(Object::getClass).distinct().collect(Collectors.toList());

				if (types.size() > 1) {
					throw new RuntimeException();
				}

				type = (Class<T>) types.get(0);
			}
		}

		return type;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation materializes the full sequence into a new array if the
	 * modulus is less than the logical length (indicating repetition). Otherwise,
	 * returns the stored array directly.
	 *
	 * @return an array containing all values in this sequence
	 */
	@Override
	public T[] toArray() {
		if (values == null || mod < len) {
			return IntStream.range(0, length())
					.mapToObj(i -> valueAt(i))
					.toArray(generator);
		}

		return values;
	}

	/**
	 * Returns the modulus (period length) of this sequence.
	 *
	 * <p>The modulus indicates how many values are stored before the pattern repeats.
	 * For constant sequences, this returns 1. For non-repeating sequences, this
	 * equals the logical length.
	 *
	 * @return the modulus of this sequence
	 */
	public long getMod() { return mod; }

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long lengthLong() { return len; }

	/**
	 * Returns a hash code for this sequence.
	 *
	 * <p>For constant sequences, returns the hash code of the single value.
	 * For non-constant sequences, returns the hash code of the stored array.
	 *
	 * @return the hash code
	 */
	@Override
	public int hashCode() {
		return values == null ? single.hashCode() : Arrays.hashCode(values);
	}

	/**
	 * Compares this sequence to another object for equality.
	 *
	 * <p>Two {@code ArrayItem} instances are equal if:
	 * <ul>
	 *   <li>Both are constant sequences with equal single values, OR</li>
	 *   <li>Both produce equal arrays via {@link #toArray()}</li>
	 * </ul>
	 *
	 * @param obj the object to compare with
	 * @return {@code true} if the objects are equal, {@code false} otherwise
	 */
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof ArrayItem)) return false;

		ArrayItem<?> it = (ArrayItem<?>) obj;
		if (values == null && Objects.equals(single, it.single)) return true;
		return Arrays.equals(toArray(), it.toArray());
	}

	/**
	 * Computes and returns the modulus of this sequence by analyzing the full array.
	 *
	 * <p>This method materializes the sequence via {@link #toArray()} and then
	 * analyzes it for repeating patterns using {@link #calculateMod(Object[])}.
	 *
	 * <p>This is useful when the sequence was created without automatic modulus
	 * calculation (i.e., when {@link #enableCalculateMod} was {@code false}).
	 *
	 * @return the computed modulus of this sequence
	 * @see #calculateMod(Object[])
	 */
	public int computeMod() {
		return calculateMod(toArray());
	}

	/**
	 * Calculates the modulus (smallest repeating period) of an array of values.
	 *
	 * <p>This static method analyzes an array to find the shortest prefix that,
	 * when repeated, produces the entire array. The algorithm:
	 * <ol>
	 *   <li>Scans the array until a value is seen that was seen before</li>
	 *   <li>Uses that position as a candidate modulus</li>
	 *   <li>Verifies that all subsequent values match their corresponding position
	 *       in the first period</li>
	 *   <li>Returns the array length if no valid repeating pattern is found</li>
	 * </ol>
	 *
	 * <p>Example:
	 * <pre>{@code
	 * calculateMod(new Integer[]{1, 2, 3, 1, 2, 3})  // Returns 3
	 * calculateMod(new Integer[]{1, 2, 3, 4})        // Returns 4
	 * calculateMod(new Integer[]{5, 5, 5, 5})        // Returns 4 (use constructor for constant detection)
	 * }</pre>
	 *
	 * @param <T>    the type of array elements
	 * @param values the array to analyze (must not be empty)
	 * @return the smallest period length, or the array length if no pattern is found
	 */
	public static <T> int calculateMod(T values[]) {
		Set<T> existing = new HashSet<>();

		int mod = -1;

		i: for (int i = 0; i < values.length; i++) {
			if (existing.size() > 1 && existing.contains(values[i])) {
				mod = i;
				break i;
			}

			existing.add(values[i]);
		}

		if (mod == -1) {
			return values.length;
		}

		for (int i = 0; i < values.length; i++) {
			int row = i / mod;

			if (row > 0) {
				int col = i % mod;
				int compareIdx = (row - 1) * mod + col;
				if (!values[compareIdx].equals(values[i])) {
					return values.length;
				}
			}
		}

		return mod;
	}
}
