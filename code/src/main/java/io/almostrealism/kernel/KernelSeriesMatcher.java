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

package io.almostrealism.kernel;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.expression.Expression;

import java.util.OptionalInt;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class KernelSeriesMatcher implements ExpressionFeatures {

	public static KernelSeriesProvider defaultProvider() {
		return defaultProvider(null);
	}

	public static KernelSeriesProvider defaultProvider(OperationMetadata metadata) {
		return defaultProvider(metadata, OptionalInt.empty());
	}

	public static KernelSeriesProvider defaultProvider(int count) {
		return defaultProvider(null, count);
	}

	public static KernelSeriesProvider defaultProvider(OperationMetadata metadata, int count) {
		return defaultProvider(metadata, OptionalInt.of(count));
	}

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

			@Override
			public String describe() {
				return getMetadata().getShortDescription();
			}
		};
	}
}
