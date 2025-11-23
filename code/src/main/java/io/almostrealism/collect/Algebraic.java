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

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Represents a data structure, system, or process that adheres to the rules of
 * linear algebra. Types implementing this interface can be interpreted as
 * transformations in a vector space, enabling algebraic optimizations during
 * computation graph construction.
 *
 * <p>This interface provides methods to classify and identify special transformation
 * types that can be optimized during code generation and execution:
 * <ul>
 *   <li><b>Zero transformations</b> - Operations that always produce zero vectors,
 *       allowing entire computation branches to be eliminated</li>
 *   <li><b>Identity transformations</b> - Operations that leave input unchanged,
 *       enabling pass-through optimizations</li>
 *   <li><b>Diagonal transformations</b> - Operations that scale vector components
 *       independently, enabling element-wise optimizations</li>
 * </ul>
 *
 * <h2>Usage Patterns</h2>
 *
 * <p>The static utility methods allow safe type checking and method invocation
 * on arbitrary objects:
 * <pre>{@code
 * // Check if a producer represents zero multiplication
 * if (Algebraic.isZero(producer)) {
 *     return zeros(shape);  // Short-circuit the computation
 * }
 *
 * // Check if a matrix is an identity transformation
 * if (Algebraic.isIdentity(vectorWidth, matrix)) {
 *     return input;  // Skip the matrix multiplication
 * }
 *
 * // Get the scalar for diagonal matrices
 * Optional<Computable> scalar = Algebraic.getDiagonalScalar(width, matrix);
 * if (scalar.isPresent()) {
 *     return multiply(input, scalar.get());  // Use scalar multiplication
 * }
 * }</pre>
 *
 * <h2>Implementation Notes</h2>
 *
 * <p>Classes implementing this interface should override the default methods
 * when the transformation type can be determined. The default implementations
 * return conservative values (false for boolean checks, empty for optionals).
 *
 * <p>This interface is commonly implemented alongside {@link IndexSet} in
 * {@link TraversableExpression} to provide both algebraic classification
 * and index-based traversal capabilities.
 *
 * @see Computable
 * @see TraversableExpression
 * @author Michael Murray
 */
public interface Algebraic extends Computable {
	/**
	 * Checks if this {@link Algebraic} represents an annihilating (zero)
	 * transformation. An annihilating transformation always produces a zero
	 * vector regardless of input, making it equivalent to multiplication by
	 * a zero matrix.
	 *
	 * <p>When this method returns {@code true}, computations involving this
	 * transformation can be optimized by short-circuiting to produce zeros
	 * directly, eliminating unnecessary computation.
	 *
	 * @return {@code true} if this transformation always produces zeros;
	 *         {@code false} otherwise or if the property cannot be determined
	 */
	default boolean isZero() {
		return false;
	}

	/**
	 * Checks if this {@link Algebraic} represents an identity transformation
	 * on vectors of the specified size. An identity transformation leaves the
	 * input vector unaltered, equivalent to multiplication by an identity matrix
	 * (a matrix with 1s on the diagonal and 0s elsewhere).
	 *
	 * <p>When this method returns {@code true}, the transformation can be
	 * eliminated entirely from the computation graph since the output equals
	 * the input.
	 *
	 * @param width the dimension of the vector space on which the transformation
	 *              operates; used to verify the transformation is identity for
	 *              vectors of this specific size
	 * @return {@code true} if this transformation is the identity for the
	 *         given width; {@code false} otherwise or if undetermined
	 */
	default boolean isIdentity(int width) {
		return false;
	}

	/**
	 * Checks if this {@link Algebraic} represents a diagonal transformation on
	 * vectors of the specified size. A diagonal transformation scales each
	 * component of the input vector independently (possibly by the same factor),
	 * equivalent to multiplication by a diagonal matrix.
	 *
	 * <p>This method returns {@code true} if either:
	 * <ul>
	 *   <li>The transformation is an identity (scaling by 1)</li>
	 *   <li>A diagonal scalar factor can be retrieved via {@link #getDiagonalScalar(int)}</li>
	 * </ul>
	 *
	 * <p>Diagonal transformations enable optimized element-wise operations
	 * instead of full matrix multiplications.
	 *
	 * @param width the dimension of the vector space on which the transformation
	 *              operates
	 * @return {@code true} if this transformation is diagonal for the given width;
	 *         {@code false} otherwise or if undetermined
	 * @see #getDiagonalScalar(int)
	 */
	default boolean isDiagonal(int width) {
		return isIdentity(width) || getDiagonalScalar(width).isPresent();
	}

	/**
	 * Retrieves the scalar factor if this {@link Algebraic} represents a uniform
	 * diagonal (scalar) transformation on vectors of the specified size. A uniform
	 * diagonal transformation scales all components of the input vector by the
	 * same constant factor.
	 *
	 * <p>When present, the returned {@link Computable} represents the scalar
	 * multiplier. This enables optimization of matrix-vector multiplication
	 * to simple scalar multiplication when the matrix is a scalar multiple
	 * of the identity matrix.
	 *
	 * <p>Note: This method does not return a value for identity transformations
	 * (scaling by 1); use {@link #isIdentity(int)} to check for that case.
	 *
	 * @param width the dimension of the vector space on which the transformation
	 *              operates
	 * @return an {@link Optional} containing the scalar factor {@link Computable}
	 *         if this is a uniform diagonal transformation; {@link Optional#empty()}
	 *         if the transformation is not a uniform diagonal or cannot be determined
	 * @see #isDiagonal(int)
	 */
	default Optional<Computable> getDiagonalScalar(int width) {
		return Optional.empty();
	}

	/**
	 * Determines if this {@link Algebraic} transformation is semantically
	 * equivalent to another. This method provides a way to compare transformations
	 * that may have different implementations but produce the same results.
	 *
	 * <p>The default implementation uses {@link Object#equals(Object)}, but
	 * subclasses may override to provide more sophisticated matching logic,
	 * such as comparing underlying data sources or mathematical properties.
	 *
	 * @param <T> the type of the other algebraic transformation
	 * @param other the {@link Algebraic} transformation to compare against
	 * @return {@code true} if this transformation matches the other;
	 *         {@code false} otherwise
	 */
	default <T extends Algebraic> boolean matches(T other) {
		return equals(other);
	}

	default OptionalDouble getConstant() {
		return OptionalDouble.empty();
	}

	/**
	 * Static utility method to check if an arbitrary value represents a zero
	 * transformation. This method performs type checking before delegating
	 * to {@link #isZero()}.
	 *
	 * @param <T> the type of the value to check
	 * @param value the value to check; may be any type
	 * @return {@code true} if the value is an {@link Algebraic} and represents
	 *         a zero transformation; {@code false} otherwise
	 */
	static <T> boolean isZero(T value) {
		return value instanceof Algebraic && ((Algebraic) value).isZero();
	}

	/**
	 * Static utility method to check if an arbitrary value represents an identity
	 * transformation for the specified width. This method performs type checking
	 * before delegating to {@link #isIdentity(int)}.
	 *
	 * @param <T> the type of the value to check
	 * @param width the dimension of the vector space
	 * @param value the value to check; may be any type
	 * @return {@code true} if the value is an {@link Algebraic} and represents
	 *         an identity transformation; {@code false} otherwise
	 */
	static <T> boolean isIdentity(int width, T value) {
		return value instanceof Algebraic && ((Algebraic) value).isIdentity(width);
	}

	/**
	 * Static utility method to check if an arbitrary value represents a diagonal
	 * transformation for the specified width. This method performs type checking
	 * before delegating to {@link #isDiagonal(int)}.
	 *
	 * @param <T> the type of the value to check
	 * @param width the dimension of the vector space
	 * @param value the value to check; may be any type
	 * @return {@code true} if the value is an {@link Algebraic} and represents
	 *         a diagonal transformation; {@code false} otherwise
	 */
	static <T> boolean isDiagonal(int width, T value) {
		return value instanceof Algebraic && ((Algebraic) value).isDiagonal(width);
	}

	/**
	 * Static utility method to retrieve the diagonal scalar factor from an
	 * arbitrary value. This method performs type checking before delegating
	 * to {@link #getDiagonalScalar(int)}.
	 *
	 * @param <T> the type of the value to check
	 * @param width the dimension of the vector space
	 * @param value the value to check; may be any type
	 * @return an {@link Optional} containing the scalar factor if the value
	 *         is an {@link Algebraic} with a uniform diagonal transformation;
	 *         {@link Optional#empty()} otherwise
	 */
	static <T> Optional<Computable> getDiagonalScalar(int width, T value) {
		if (!(value instanceof Algebraic)) {
			return Optional.empty();
		}

		return ((Algebraic) value).getDiagonalScalar(width);
	}

	static <T> OptionalDouble getConstant(T value) {
		if (!(value instanceof Algebraic)) {
			return OptionalDouble.empty();
		}

		return ((Algebraic) value).getConstant();
	}
}

