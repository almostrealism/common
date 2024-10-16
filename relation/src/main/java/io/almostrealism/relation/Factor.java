/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.relation;

import io.almostrealism.uml.Signature;

/**
 * A {@link Factor} transforms a computational system represented by one {@link Producer},
 * into another, while preserving the type of the ultimate result.
 *
 * @param <T>  The type of the ultimate result of computation.
 */
@FunctionalInterface
public interface Factor<T> extends Function<T, T>, Signature {
	Producer<T> getResultant(Producer<T> value);

	default Factor<T> andThen(Factor<T> next) {
		throw new UnsupportedOperationException();
	}
}
