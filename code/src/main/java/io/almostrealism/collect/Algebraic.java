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

package io.almostrealism.collect;

import io.almostrealism.relation.Computable;

/**
 * Represents a data-structure, system or process that adheres to the rules of
 * linear algebra. Types implementing this interface can be interpreted as
 * transformations in a vector space.
 */
public interface Algebraic extends Computable {
	/**
	 * Checks if this {@link Algebraic} {@link Computable} represents an annihilating
	 * transformation on vectors of the specified size. An Annihilating transformation
	 * always produces a zero length vector.
	 */
	default boolean isZero() {
		return false;
	}

	/**
	 * Checks if this {@link Algebraic} {@link Computable} represents an identity
	 * transformation on vectors of the specified size. An identity transformation
	 * leaves the input vector unaltered.
	 */
	default boolean isIdentity(int width) {
		return false;
	}

	/**
	 * Checks if this {@link Algebraic} {@link Computable} represents a diagonal
	 * transformation on vectors of the specified size. A diagonal transformation
	 * scales the input vector by a constant factor.
	 */
	default boolean isDiagonal(int width) { return isIdentity(width); }

	static <T> boolean isZero(T value) {
		return value instanceof Algebraic && ((Algebraic) value).isZero();
	}

	static <T> boolean isIdentity(int width, T value) {
		return value instanceof Algebraic && ((Algebraic) value).isIdentity(width);
	}

	static <T> boolean isDiagonal(int width, T value) {
		return value instanceof Algebraic && ((Algebraic) value).isDiagonal(width);
	}
}

