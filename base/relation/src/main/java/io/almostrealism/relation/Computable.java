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

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A marker interface for types that represent computational activities requiring
 * computing resources.
 *
 * <p>{@link Computable} distinguishes types that <em>perform</em> computation from
 * types that are merely <em>operated upon</em> by computation. This distinction
 * is fundamental to the Almost Realism framework:</p>
 *
 * <ul>
 *   <li><b>Computable types:</b> {@link Producer}, {@link Evaluable}, operations</li>
 *   <li><b>Non-computable types:</b> Data containers, tensors, collections</li>
 * </ul>
 *
 * <h2>Purpose</h2>
 * <p>The {@link Computable} interface serves as a tagging mechanism that enables:</p>
 * <ul>
 *   <li>Type-safe handling of computation vs. data</li>
 *   <li>Identification of constant (compile-time known) computations</li>
 *   <li>Framework-level optimizations based on computation characteristics</li>
 * </ul>
 *
 * <h2>Constant Computations</h2>
 * <p>A computation is considered <em>constant</em> if its result is known at
 * compile time and does not depend on runtime inputs. Constant computations
 * can be evaluated once and their results inlined, avoiding repeated computation.</p>
 *
 * <h2>Algebraic Classification</h2>
 * <p>A computation can also be interpreted as a transformation in a vector space, and
 * a computation that knows it is one of the special transformations below reports so,
 * enabling algebraic optimizations during computation graph construction:</p>
 * <ul>
 *   <li><b>Zero transformations</b> ({@link #isZero()}) - always produce zero vectors,
 *       allowing entire computation branches to be eliminated</li>
 *   <li><b>Identity transformations</b> ({@link #isIdentity(int)}) - leave input
 *       unchanged, enabling pass-through optimizations</li>
 *   <li><b>Diagonal transformations</b> ({@link #isDiagonal(int)},
 *       {@link #getDiagonalScalar(int)}) - scale vector components independently,
 *       enabling element-wise optimizations</li>
 *   <li><b>Row-monomial transformations</b> ({@link #isRowMonomial()}) - have at most
 *       one non-zero entry per row, so a contraction against them collapses to a gather</li>
 *   <li><b>Uniform scalars</b> ({@link #getConstant()}) - scale every component by the
 *       same known value</li>
 * </ul>
 * <p>The defaults are conservative ({@code false} or empty), so any computation can be
 * asked and only those that can determine a property override the query:</p>
 * <pre>{@code
 * if (producer.isZero()) {
 *     return zeros(shape);  // Short-circuit the computation
 * }
 *
 * if (matrix.isIdentity(vectorWidth)) {
 *     return input;  // Skip the matrix multiplication
 * }
 * }</pre>
 *
 * @see Producer
 * @see Evaluable
 *
 * @author Michael Murray
 */
public interface Computable {
	/**
	 * Returns {@code true} if this computation produces a constant result.
	 *
	 * <p>A constant computation is one whose result is independent of runtime
	 * inputs and can be computed at compile time. Examples include:</p>
	 * <ul>
	 *   <li>Literal values wrapped in computation</li>
	 *   <li>Pure computations on other constants</li>
	 *   <li>Pre-computed lookup tables</li>
	 * </ul>
	 *
	 * <p>Identifying constant computations enables optimizations such as
	 * constant folding and compile-time evaluation.</p>
	 *
	 * @return {@code true} if this computation is constant, {@code false} otherwise
	 */
	default boolean isConstant() {
		return false;
	}

	/** Returns {@code true} if this computation provides a value reference. */
	default boolean isProvider() { return false; }

	/**
	 * Returns {@code true} if this computation can participate directly in kernel-level
	 * evaluation without additional wrapping. Algebraic simplifications that replace one
	 * operand with another are only applied to directly computable operands.
	 *
	 * @return {@code true} if this computation is directly computable, {@code false} otherwise
	 */
	default boolean isDirectlyComputable() { return false; }

	/**
	 * Returns {@code true} if this computation is itself a reduction, combining groups
	 * of its input elements into single outputs (an aggregation or a weighted sum). A
	 * reduction already compiles to an accumulation, so a reduction applied to its
	 * output is not re-expressed in another form.
	 *
	 * @return {@code true} if this computation reduces its input, {@code false} otherwise
	 */
	default boolean isReduction() { return false; }

	/**
	 * Determines whether this computation is semantically equivalent to another, which
	 * may have a different implementation but produces the same results.
	 *
	 * <p>The default implementation uses {@link Object#equals(Object)}; implementations
	 * override it to compare what actually identifies their value, such as an underlying
	 * data source or a referenced kernel argument.</p>
	 *
	 * @param other the computation to compare against
	 * @return {@code true} if this computation matches the other, {@code false} otherwise
	 */
	default boolean matches(Computable other) {
		return equals(other);
	}

	/**
	 * Checks if this computation represents an annihilating (zero) transformation. An
	 * annihilating transformation always produces a zero vector regardless of input,
	 * making it equivalent to multiplication by a zero matrix.
	 *
	 * <p>When this method returns {@code true}, computations involving this one can be
	 * optimized by short-circuiting to produce zeros directly.</p>
	 *
	 * @return {@code true} if this computation always produces zeros; {@code false}
	 *         otherwise or if the property cannot be determined
	 */
	default boolean isZero() {
		return false;
	}

	/**
	 * Checks if this computation represents an identity transformation on vectors of the
	 * specified size, equivalent to multiplication by an identity matrix. When this method
	 * returns {@code true} the transformation can be eliminated from the computation graph,
	 * since its output equals its input.
	 *
	 * @param width the dimension of the vector space on which the transformation operates
	 * @return {@code true} if this computation is the identity for the given width;
	 *         {@code false} otherwise or if undetermined
	 */
	default boolean isIdentity(int width) {
		return false;
	}

	/**
	 * Checks if this computation represents a diagonal transformation on vectors of the
	 * specified size: one that scales each component of the input independently (possibly
	 * by the same factor), equivalent to multiplication by a diagonal matrix.
	 *
	 * <p>The default returns {@code true} if the transformation is an identity or a
	 * diagonal scalar factor can be retrieved via {@link #getDiagonalScalar(int)}.</p>
	 *
	 * @param width the dimension of the vector space on which the transformation operates
	 * @return {@code true} if this computation is diagonal for the given width;
	 *         {@code false} otherwise or if undetermined
	 * @see #getDiagonalScalar(int)
	 */
	default boolean isDiagonal(int width) {
		return isIdentity(width) || getDiagonalScalar(width).isPresent();
	}

	/**
	 * Retrieves the scalar factor if this computation represents a uniform diagonal
	 * (scalar) transformation on vectors of the specified size, which scales every
	 * component of the input by the same factor. This allows a matrix-vector product to
	 * be replaced by a scalar multiplication.
	 *
	 * <p>This method does not return a value for identity transformations (scaling by 1);
	 * use {@link #isIdentity(int)} to check for that case.</p>
	 *
	 * @param width the dimension of the vector space on which the transformation operates
	 * @return the scalar factor if this is a uniform diagonal transformation;
	 *         {@link Optional#empty()} if it is not or cannot be determined
	 * @see #isDiagonal(int)
	 */
	default Optional<Computable> getDiagonalScalar(int width) {
		return Optional.empty();
	}

	/**
	 * Checks if this computation represents a <em>row-monomial</em> transformation: a
	 * matrix in which every row has at most one non-zero entry (equivalently, each row is
	 * either zero or a scalar multiple of a standard basis vector).
	 *
	 * <p>This generalizes {@link #isIdentity(int)} and {@link #isDiagonal(int)}: a diagonal
	 * matrix is the row-monomial matrix whose (at most one) non-zero per row sits on the
	 * diagonal. A row-monomial matrix relaxes "on the diagonal" to "at an arbitrary,
	 * computable column," and it need not be square: the (output x input) Jacobian of a
	 * subset, slice, or gather is a row-monomial matrix. A row that is entirely zero is
	 * consistent with the property, since a downstream contraction still gathers the true
	 * (possibly zero) value at the candidate column rather than assuming it is non-zero.</p>
	 *
	 * <p>A contraction against a row-monomial matrix (summing over the column index)
	 * collapses to a direct gather of at most one element per row, eliminating the dense
	 * reduction loop. When this method returns {@code true} the containing computation can
	 * be kept visible (rather than isolated) so that downstream reduction machinery can
	 * perform that collapse.</p>
	 *
	 * <p>No dimension argument is taken: a row-monomial matrix is generally non-square, so
	 * an implementing computation determines the property from its own shape.</p>
	 *
	 * @return {@code true} if this transformation has at most one non-zero entry per row;
	 *         {@code false} otherwise or if the property cannot be determined
	 */
	default boolean isRowMonomial() {
		return false;
	}

	/**
	 * Returns the constant scalar value of this computation if it uniformly produces the
	 * same known value for every component, or empty if no such scalar exists.
	 *
	 * @return the constant scalar value, or empty if not a uniform constant
	 */
	default OptionalDouble getConstant() {
		return OptionalDouble.empty();
	}

	/**
	 * Returns {@code true} if the given object is a {@link Computable} with a constant value.
	 *
	 * @param c   the object to check
	 * @param <T> the object type
	 * @return {@code true} if the object is a constant computable
	 */
	static <T> boolean constant(T c) {
		if (c instanceof Computable) {
			return ((Computable) c).isConstant();
		}

		return false;
	}

	/**
	 * Returns {@code true} if the given object is a {@link Computable} that is a provider.
	 *
	 * @param c   the object to check
	 * @param <T> the object type
	 * @return {@code true} if the object is a provider computable
	 */
	static <T> boolean provider(T c) {
		if (c instanceof Computable) {
			return ((Computable) c).isProvider();
		}

		return false;
	}

	/**
	 * Returns {@code true} if the given object is a {@link Computable} that declares itself
	 * row-monomial (see {@link #isRowMonomial()}).
	 *
	 * @param c   the object to check
	 * @param <T> the object type
	 * @return {@code true} if the object is a row-monomial computable
	 */
	static <T> boolean rowMonomial(T c) {
		if (c instanceof Computable) {
			return ((Computable) c).isRowMonomial();
		}

		return false;
	}
}
