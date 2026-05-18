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

package io.almostrealism.sequence;

import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.profile.OperationMetadata;

import java.util.OptionalInt;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Factory for creating default {@link KernelSeriesProvider} instances.
 *
 * <p>{@code KernelSeriesMatcher} provides static factory methods for constructing
 * {@link KernelSeriesProvider} objects that simply delegate to
 * {@link IndexSequence#getExpression(io.almostrealism.expression.Expression, boolean)}
 * for pattern detection and expression generation. It is the standard provider used
 * when no custom series analysis is needed.</p>
 *
 * @see KernelSeriesProvider
 * @see IndexSequence
 */
public class KernelSeriesMatcher implements ExpressionFeatures {

	/**
	 * Creates a default kernel series provider with no metadata and no maximum length.
	 *
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider() {
		return defaultProvider(null);
	}

	/**
	 * Creates a default kernel series provider with the given metadata and no maximum length.
	 *
	 * @param metadata the operation metadata to associate with the provider
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider(OperationMetadata metadata) {
		return defaultProvider(metadata, OptionalInt.empty());
	}

	/**
	 * Creates a default kernel series provider with no metadata and the given maximum length.
	 *
	 * @param count the maximum number of elements to process
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider(int count) {
		return defaultProvider(null, count);
	}

	/**
	 * Creates a default kernel series provider with the given metadata and maximum length.
	 *
	 * @param metadata the operation metadata to associate with the provider
	 * @param count the maximum number of elements to process
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider(OperationMetadata metadata, int count) {
		return defaultProvider(metadata, OptionalInt.of(count));
	}

	/**
	 * Creates a default kernel series provider with the given metadata and optional maximum length.
	 *
	 * <p>The returned provider delegates series generation directly to
	 * {@link IndexSequence#getExpression(io.almostrealism.expression.Expression, boolean)}.
	 *
	 * @param metadata the operation metadata to associate with the provider
	 * @param count the maximum number of elements to process, or empty for no limit
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider(OperationMetadata metadata, OptionalInt count) {
		return new KernelSeriesProvider() {
			@Override
			public OperationMetadata getMetadata() {
				return metadata;
			}

			@Override
			public Expression getSeries(Expression index, Supplier<String> exp, Supplier<IndexSequence> seq, boolean isInt, IntSupplier nodes) {
				return seq.get().getExpression(index, isInt);
			}

			@Override
			public OptionalInt getMaximumLength() {
				return count;
			}
		};
	}
}
