/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.kernel;

import java.util.OptionalLong;

/**
 * A generator that produces sequences of numeric index values.
 *
 * <p>{@code SequenceGenerator} is a core interface in the kernel evaluation system that
 * defines how index values are computed and bounded. It provides methods for:
 * <ul>
 *   <li>Computing numeric values at specific index positions</li>
 *   <li>Determining upper and lower bounds of the generated sequence</li>
 *   <li>Generating complete {@link IndexSequence} instances for a given length</li>
 * </ul>
 *
 * <p>This interface is implemented by {@link Index} and its subtypes such as
 * {@link KernelIndex}, and is also used by expression classes to generate
 * index sequences for kernel operations.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Generate a sequence from a SequenceGenerator
 * SequenceGenerator generator = ...;
 * IndexSequence seq = generator.sequence(index, 1024);
 *
 * // Access bounds information
 * OptionalLong upper = generator.upperBound();
 * OptionalLong lower = generator.lowerBound();
 *
 * // Compute a specific value
 * Number val = generator.value(new IndexValues().put(index, 42));
 * }</pre>
 *
 * @author Michael Murray
 * @see Index
 * @see IndexSequence
 * @see IndexValues
 */
public interface SequenceGenerator {

	/**
	 * Returns the limit (exclusive upper bound + 1) of this sequence generator.
	 *
	 * <p>The limit represents the total number of valid index positions,
	 * calculated as {@code upperBound + 1}. This is useful for determining
	 * the range of valid indices when iterating over the sequence.
	 *
	 * @return an {@code OptionalLong} containing the limit if the upper bound
	 *         is known, or empty if the bound cannot be determined
	 */
	default OptionalLong getLimit() {
		OptionalLong upperBound = upperBound(null);
		if (upperBound.isEmpty()) return OptionalLong.empty();
		return OptionalLong.of(upperBound.getAsLong() + 1);
	}

	/**
	 * Returns the upper bound of values produced by this generator.
	 *
	 * <p>This is a convenience method that calls {@link #upperBound(KernelStructureContext)}
	 * with a {@code null} context.
	 *
	 * @return an {@code OptionalLong} containing the upper bound if known,
	 *         or empty if the bound cannot be determined
	 * @see #upperBound(KernelStructureContext)
	 */
	default OptionalLong upperBound() { return upperBound(null); }

	/**
	 * Returns the lower bound of values produced by this generator.
	 *
	 * <p>This is a convenience method that calls {@link #lowerBound(KernelStructureContext)}
	 * with a {@code null} context.
	 *
	 * @return an {@code OptionalLong} containing the lower bound if known,
	 *         or empty if the bound cannot be determined
	 * @see #lowerBound(KernelStructureContext)
	 */
	default OptionalLong lowerBound() { return lowerBound(null); }

	/**
	 * Returns the upper bound of values produced by this generator within the given context.
	 *
	 * <p>The context provides additional information about the kernel structure
	 * that may be needed to determine tighter bounds.
	 *
	 * @param context the kernel structure context, or {@code null} for context-free bounds
	 * @return an {@code OptionalLong} containing the upper bound if known,
	 *         or empty if the bound cannot be determined
	 */
	OptionalLong upperBound(KernelStructureContext context);

	/**
	 * Returns the lower bound of values produced by this generator within the given context.
	 *
	 * <p>The context provides additional information about the kernel structure
	 * that may be needed to determine tighter bounds.
	 *
	 * @param context the kernel structure context, or {@code null} for context-free bounds
	 * @return an {@code OptionalLong} containing the lower bound if known,
	 *         or empty if the bound cannot be determined
	 */
	OptionalLong lowerBound(KernelStructureContext context);

	/**
	 * Computes the numeric value at a specific index position.
	 *
	 * <p>The index position is specified via {@link IndexValues}, which maps
	 * index variables to their concrete integer values.
	 *
	 * @param indexValues the mapping of index variables to their values
	 * @return the computed numeric value at the specified position
	 */
	Number value(IndexValues indexValues);

	/**
	 * Generates an {@link IndexSequence} of the specified length.
	 *
	 * <p>This is a convenience method that calls {@link #sequence(Index, long, long)}
	 * with {@code limit} equal to {@code len}.
	 *
	 * @param index the index variable to use for sequence generation
	 * @param len the desired length of the sequence
	 * @return an {@code IndexSequence} containing the generated values
	 * @see #sequence(Index, long, long)
	 */
	default IndexSequence sequence(Index index, long len) { return sequence(index, len, len); }

	/**
	 * Generates an {@link IndexSequence} of the specified length with a limit.
	 *
	 * <p>The generated sequence contains values computed by evaluating this
	 * generator at each index position from 0 to {@code len - 1}.
	 *
	 * @param index the index variable to use for sequence generation
	 * @param len the desired length of the sequence
	 * @param limit the maximum number of distinct values to compute (for optimization);
	 *              may be less than {@code len} if the sequence repeats
	 * @return an {@code IndexSequence} containing the generated values
	 */
	IndexSequence sequence(Index index, long len, long limit);
}
