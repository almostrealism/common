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

package io.almostrealism.expression;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.kernel.ArithmeticIndexSequence;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Represents an arithmetic sequence generator expression optimized for kernel index computations.
 * <p>
 * The {@code ArithmeticGenerator} encapsulates the mathematical formula:
 * </p>
 * <pre>
 *     result = scale * ((index % mod) / granularity)
 * </pre>
 * <p>
 * This expression is specifically designed to optimize index calculations in parallel
 * computing contexts, where indices follow regular arithmetic patterns. By explicitly
 * modeling the scale, granularity, and modulus parameters, the system can:
 * </p>
 * <ul>
 *   <li>Generate efficient {@link ArithmeticIndexSequence} instances for vectorized operations</li>
 *   <li>Perform algebraic optimizations when combining multiple arithmetic generators</li>
 *   <li>Simplify expressions during compile-time analysis</li>
 * </ul>
 *
 * <h2>Mathematical Model</h2>
 * <p>
 * Given an index variable {@code i}, the expression computes:
 * </p>
 * <pre>
 *     output(i) = scale * floor((i % mod) / granularity)
 * </pre>
 * <p>
 * This pattern commonly appears in:
 * </p>
 * <ul>
 *   <li>Array index calculations with stride patterns</li>
 *   <li>Tiled/blocked matrix operations</li>
 *   <li>Cyclic data access patterns in GPU kernels</li>
 *   <li>Memory layout transformations</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create an arithmetic generator for index pattern: 2 * ((i % 16) / 4)
 * // This generates sequence: 0,0,0,0, 2,2,2,2, 4,4,4,4, 6,6,6,6, 0,0,0,0, ...
 * Expression<?> indexExpr = new KernelIndex();
 * Expression<?> generator = ArithmeticGenerator.create(indexExpr, 2, 4, 16);
 * }</pre>
 *
 * <h2>Optimization Behavior</h2>
 * <p>
 * This class extends {@link Product} and overrides arithmetic operations to maintain
 * the arithmetic generator form where possible:
 * </p>
 * <ul>
 *   <li>{@link #add(Expression)}: Combines compatible arithmetic generators</li>
 *   <li>{@link #multiply(Expression)}: Scales the generator when multiplied by a constant</li>
 *   <li>{@link #divide(Expression)}: Adjusts granularity or scale when divided by a constant</li>
 * </ul>
 *
 * @param <T> the numeric type of this expression (typically {@code Integer} or {@code Long})
 *
 * @see Product
 * @see ArithmeticIndexSequence
 * @see Index
 *
 * @author Michael Murray
 */
public class ArithmeticGenerator<T extends Number> extends Product<T> {

	/**
	 * The index expression (typically a kernel index) that drives this generator.
	 */
	private final Expression<?> index;

	/**
	 * The multiplicative scale factor applied to the computed index value.
	 * The final output is multiplied by this value.
	 */
	private final long scale;

	/**
	 * The granularity (divisor) that determines how many consecutive index values
	 * produce the same output value. Higher granularity means coarser stepping.
	 */
	private final long granularity;

	/**
	 * The modulus that defines the period of the arithmetic sequence.
	 * The index is taken modulo this value before further computation.
	 */
	private final long mod;

	/**
	 * Constructs a new arithmetic generator with the specified parameters.
	 * <p>
	 * This constructor is protected; use the factory method {@link #create(Expression, long, long, long)}
	 * which performs validation and optimization.
	 * </p>
	 *
	 * @param index       the index expression that drives this generator
	 * @param scale       the multiplicative scale factor for the output
	 * @param granularity the divisor that controls step size (must be positive)
	 * @param mod         the modulus defining the sequence period (must be positive)
	 */
	protected ArithmeticGenerator(Expression<?> index, long scale, long granularity, long mod) {
		super(List.of(new Quotient(List.of(index.imod(mod),
						ExpressionFeatures.getInstance().e(granularity))),
				ExpressionFeatures.getInstance().e(scale)));
		this.index = index;
		this.scale = scale;
		this.granularity = granularity;
		this.mod = mod;
	}

	/**
	 * Returns the index expression that drives this generator.
	 *
	 * @return the index expression
	 */
	public Expression<?> getIndex() { return index; }

	/**
	 * Returns the multiplicative scale factor.
	 *
	 * @return the scale value
	 */
	public long getScale() { return scale; }

	/**
	 * Returns the granularity (divisor) that controls the step size.
	 *
	 * @return the granularity value
	 */
	public long getGranularity() { return granularity; }

	/**
	 * Returns the modulus that defines the sequence period.
	 *
	 * @return the modulus value
	 */
	public long getMod() { return mod; }

	/**
	 * Generates an index sequence for this arithmetic generator.
	 * <p>
	 * If the provided index matches this generator's index expression, an optimized
	 * {@link ArithmeticIndexSequence} is returned that can efficiently compute values
	 * without evaluating the full expression tree for each index position.
	 * </p>
	 *
	 * @param index the index to generate a sequence for
	 * @param len   the length of the sequence to generate
	 * @param limit the maximum number of elements to compute
	 * @return an {@link IndexSequence} representing the computed values, or the result
	 *         of {@code super.sequence()} if the index does not match
	 */
	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		if (Objects.equals(index, getIndex())) {
			return new ArithmeticIndexSequence(getScale(), getGranularity(), getMod(), len);
		}

		return super.sequence(index, len, limit);
	}

	/**
	 * Adds another expression to this arithmetic generator.
	 * <p>
	 * This method performs algebraic optimizations when possible:
	 * </p>
	 * <ul>
	 *   <li>If the operand is zero, returns this generator unchanged</li>
	 *   <li>If the operand is another {@code ArithmeticGenerator} with the same index,
	 *       granularity, and modulus, the scales are combined</li>
	 *   <li>Special cases are handled for compatible generator combinations</li>
	 * </ul>
	 * <p>
	 * When optimization is not possible, falls back to creating a {@link Sum} expression.
	 * </p>
	 *
	 * @param operand the expression to add to this generator
	 * @return an optimized expression if possible, otherwise a {@link Sum} containing
	 *         this generator and the operand
	 */
	@Override
	public Expression<? extends Number> add(Expression<?> operand) {
		if (operand.intValue().orElse(1) == 0) {
			return this;
		} else if (operand instanceof ArithmeticGenerator) {
			ArithmeticGenerator<?> ag = (ArithmeticGenerator<?>) operand;

			if (Objects.equals(getIndex(), ag.getIndex())) {
				if (getGranularity() == ag.getGranularity() && getMod() == ag.getMod()) {
					return ArithmeticGenerator.create(getIndex(), getScale() + ag.getScale(), getGranularity(), getMod());
				} else if (getMod() % ag.getMod() == 0 && ag.getMod() == getScale() &&
						getScale() == getGranularity() && ag.getScale() == ag.getGranularity()) {
					return ArithmeticGenerator.create(getIndex(), ag.getScale(), ag.getGranularity(), getMod());
				}
			}
		}

		return new Sum(List.of(this, operand));
	}

	/**
	 * Multiplies this arithmetic generator by another expression.
	 * <p>
	 * When the operand is a constant value, the multiplication is absorbed into the
	 * generator's scale factor, producing a new optimized {@code ArithmeticGenerator}.
	 * This preserves the arithmetic generator form for further optimizations.
	 * </p>
	 * <p>
	 * When the operand is not a constant, falls back to creating a {@link Product} expression.
	 * </p>
	 *
	 * @param operand the expression to multiply with this generator
	 * @return a new {@code ArithmeticGenerator} with scaled output if the operand is constant,
	 *         otherwise a {@link Product} expression
	 */
	@Override
	public Expression<? extends Number> multiply(Expression<?> operand) {
		OptionalLong d = operand.longValue();

		if (d.isPresent()) {
			return ArithmeticGenerator.create(getIndex(), getScale() * d.getAsLong(), getGranularity(), getMod());
		}

		return new Product(List.of(this, operand));
	}

	/**
	 * Divides this arithmetic generator by another expression.
	 * <p>
	 * This method performs several optimizations when the operand is a constant value:
	 * </p>
	 * <ul>
	 *   <li>If the generator's upper bound is less than the divisor (and non-negative),
	 *       the result is always zero</li>
	 *   <li>If the scale is 1, the granularity is increased by the divisor factor</li>
	 *   <li>If the scale is evenly divisible by the operand, the scale is reduced</li>
	 * </ul>
	 * <p>
	 * For non-constant operands or when optimizations don't apply, falls back to
	 * creating a {@link Quotient} expression.
	 * </p>
	 *
	 * @param operand the expression to divide this generator by
	 * @return an optimized expression when possible, otherwise a {@link Quotient} expression
	 */
	@Override
	public Expression<? extends Number> divide(Expression<?> operand) {
		OptionalLong d = operand.longValue();

		if (d.isPresent()) {
			long bound = upperBound().orElse(Long.MAX_VALUE);

			if (!isPossiblyNegative() && bound < d.getAsLong()) {
				return new IntegerConstant(0);
			} else if (getScale() == 1) {
				return ArithmeticGenerator.create(getIndex(), 1, d.getAsLong() * getGranularity(), getMod());
			} else if (getScale() % d.getAsLong() == 0) {
				return ArithmeticGenerator.create(getIndex(), getScale() / d.getAsLong(), getGranularity(), getMod());
			}
		}

		if (getScale() == 1) {
			return getIndex().imod(getMod()).divide(operand);
		}

		return new Quotient<>(List.of(this, operand));
	}

	/**
	 * Compares this arithmetic generator for equality with another expression.
	 * <p>
	 * Currently delegates to the superclass implementation. Future optimizations
	 * may be added to handle special cases more efficiently.
	 * </p>
	 *
	 * @param operand the expression to compare against
	 * @return an {@link Expression} representing the equality comparison result
	 */
	@Override
	public Expression eq(Expression<?> operand) {
		// TODO Optimization
		return super.eq(operand);
	}

	/**
	 * Factory method for creating an {@code ArithmeticGenerator} with validation.
	 * <p>
	 * This method performs validation and optimization before creating the generator:
	 * </p>
	 * <ul>
	 *   <li>If the granularity is greater than or equal to the modulus (in absolute terms)
	 *       and the index is non-negative, the result is always zero because the quotient
	 *       of a value less than the divisor is always zero</li>
	 *   <li>Otherwise, creates a new {@code ArithmeticGenerator} with the specified parameters</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * // Generator for pattern: 3 * ((i % 12) / 4)
	 * // Sequence: 0,0,0,0, 3,3,3,3, 6,6,6,6, 0,0,0,0, ...
	 * Expression<?> gen = ArithmeticGenerator.create(indexExpr, 3, 4, 12);
	 *
	 * // Returns zero because granularity >= mod
	 * Expression<?> zero = ArithmeticGenerator.create(indexExpr, 1, 16, 8);
	 * }</pre>
	 *
	 * @param index       the index expression that drives the generator
	 * @param scale       the multiplicative scale factor
	 * @param granularity the divisor that controls step size
	 * @param mod         the modulus defining the sequence period
	 * @return a new {@code ArithmeticGenerator} if the parameters are valid,
	 *         or an {@link IntegerConstant} of zero if the result would always be zero
	 */
	public static Expression<? extends Number> create(Expression<?> index, long scale, long granularity, long mod) {
		if (!index.isPossiblyNegative() && Math.abs(granularity) >= Math.abs(mod)) {
			// If the granularity is greater than the modulus, the result would
			// divide an expression which is always below the denominoator and
			// hence is always zero
			return new IntegerConstant(0);
		}

		return new ArithmeticGenerator<>(index, scale, granularity, mod);
	}
}
