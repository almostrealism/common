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

import io.almostrealism.code.Precision;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Represents a type cast expression that converts a value from one type to another.
 * <p>
 * This class extends {@link UnaryExpression} to provide type casting functionality
 * in generated code. It supports casting between numeric types including int, long,
 * and double (floating point). The cast expression generates language-appropriate
 * syntax such as {@code (int)} or {@code (double)} in the output code.
 * </p>
 * <p>
 * The class includes optimizations for constant folding - when the operand has a
 * known constant value, the cast is evaluated at compile time and the result is
 * returned as an appropriate constant expression.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Expression<?> doubleExpr = new DoubleConstant(3.14);
 * Expression<Integer> intExpr = Cast.of(Integer.class, Cast.INT_NAME, doubleExpr);
 * // Results in: (int) 3.14 -> IntegerConstant(3)
 * }</pre>
 *
 * @param <T> the target type of the cast expression
 * @see UnaryExpression
 * @see DoubleConstant
 * @see IntegerConstant
 * @see LongConstant
 */
public class Cast<T> extends UnaryExpression<T> {
	/** Type name constant for floating point (double) casts. */
	public static final String FP_NAME = "double";
	/** Type name constant for integer casts. */
	public static final String INT_NAME = "int";
	/** Type name constant for long integer casts. */
	public static final String LONG_NAME = "long";

	/** The target type name for this cast operation. */
	private String typeName;

	/**
	 * Constructs a new Cast expression.
	 *
	 * @param type     the Java class representing the target type
	 * @param typeName the string name of the target type (e.g., "int", "double", "long")
	 * @param operand  the expression to be cast
	 * @throws IllegalArgumentException if typeName is null
	 */
	protected Cast(Class<T> type, String typeName, Expression<?> operand) {
		super(type, "(" + typeName + ")", operand);
		this.typeName = typeName;

		if (typeName == null) {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Returns the target type name for this cast.
	 *
	 * @return the type name string (e.g., "int", "double", "long")
	 */
	public String getTypeName() { return typeName; }

	/**
	 * Returns the cast operator string for the target language.
	 * <p>
	 * If the cast is to "double" but the language precision is not FP64,
	 * this returns "(float)" instead of "(double)" to match the target precision.
	 * </p>
	 *
	 * @param lang the language operations context
	 * @return the cast operator string including parentheses
	 */
	@Override
	protected String getOperator(LanguageOperations lang) {
		if (FP_NAME.equals(getTypeName()) && lang.getPrecision() != Precision.FP64) {
			return "(float)";
		}

		return "(" + getTypeName() + ")";
	}

	/**
	 * Attempts to compute the integer value of this cast expression.
	 * <p>
	 * If the child expression has a known integer value, returns it directly.
	 * If this is an integer cast and the child has a known double value,
	 * performs the conversion and returns the result.
	 * </p>
	 *
	 * @return an OptionalInt containing the integer value if computable, otherwise empty
	 */
	@Override
	public OptionalInt intValue() {
		OptionalInt i = getChildren().get(0).intValue();
		if (i.isPresent()) return i;
		if (getType() == Integer.class) {
			OptionalDouble d = getChildren().get(0).doubleValue();
			if (d.isPresent()) return OptionalInt.of((int) d.getAsDouble());
		}
		return super.intValue();
	}

	/**
	 * Attempts to compute the long value of this cast expression.
	 * <p>
	 * If this is a long cast and the child has a known double value,
	 * performs the conversion and returns the result.
	 * </p>
	 *
	 * @return an OptionalLong containing the long value if computable, otherwise empty
	 */
	@Override
	public OptionalLong longValue() {
		OptionalLong l = super.longValue();
		if (l.isPresent()) return l;

		if (getType() == Long.class) {
			OptionalDouble d = getChildren().get(0).doubleValue();
			if (d.isPresent()) return OptionalLong.of((long) d.getAsDouble());
		}

		return l;
	}

	/**
	 * Attempts to compute the double value of this cast expression.
	 * <p>
	 * First attempts to get the long value (which may involve conversion),
	 * then falls back to the child's double value if this is a double cast.
	 * </p>
	 *
	 * @return an OptionalDouble containing the double value if computable, otherwise empty
	 */
	@Override
	public OptionalDouble doubleValue() {
		OptionalLong l = longValue();
		if (l.isPresent()) return OptionalDouble.of(l.getAsLong());

		if (getType() == Double.class) {
			OptionalDouble d = getChildren().get(0).doubleValue();
			if (d.isPresent()) return d;
		}

		return super.doubleValue();
	}

	/**
	 * Determines if this cast expression represents a constant value given the provided index values.
	 * <p>
	 * Delegates to the child expression since a cast of a constant is still a constant.
	 * </p>
	 *
	 * @param values the index values context
	 * @return true if the child expression is a constant value
	 */
	@Override
	public boolean isValue(IndexValues values) {
		return getChildren().get(0).isValue(values);
	}

	/**
	 * Returns the kernel series representation of this cast expression.
	 * <p>
	 * Delegates to the child expression since the cast does not change
	 * the kernel iteration structure.
	 * </p>
	 *
	 * @return the kernel series of the child expression
	 */
	@Override
	public KernelSeries kernelSeries() {
		return getChildren().get(0).kernelSeries();
	}

	/**
	 * Computes the numeric value of this cast expression given specific index values.
	 * <p>
	 * Evaluates the child expression and applies the appropriate type conversion
	 * based on the target type name.
	 * </p>
	 *
	 * @param indexValues the index values to use for evaluation
	 * @return the computed value as an Integer for "int" casts, Double otherwise
	 */
	@Override
	public Number value(IndexValues indexValues) {
		double v = getChildren().get(0).value(indexValues).doubleValue();

		if (typeName.equals("int")) {
			return Integer.valueOf((int) v);
		} else {
			return Double.valueOf(v);
		}
	}

	/**
	 * Evaluates this cast operation on the provided child values.
	 * <p>
	 * Applies the type conversion based on the target type name.
	 * </p>
	 *
	 * @param children the child expression values (expects exactly one)
	 * @return the converted value as an Integer for "int" casts, Double otherwise
	 */
	@Override
	public Number evaluate(Number... children) {
		if (typeName.equals("int")) {
			return Integer.valueOf(children[0].intValue());
		} else {
			return Double.valueOf(children[0].doubleValue());
		}
	}

	/**
	 * Creates a new Cast expression with the specified children.
	 *
	 * @param children the new child expressions (must contain exactly one element)
	 * @return a new Cast expression with the same type and type name
	 * @throws UnsupportedOperationException if children does not contain exactly one element
	 */
	@Override
	public Expression<T> recreate(List children) {
		if (children.size() != 1) throw new UnsupportedOperationException();
		return Cast.of(getType(), typeName, (Expression) children.get(0));
	}

	/**
	 * Factory method to create a Cast expression with constant folding optimization.
	 * <p>
	 * If the input value has a known double value, the cast is performed at compile time
	 * and an appropriate constant expression is returned:
	 * <ul>
	 *   <li>{@code FP_NAME} ("double") - returns a {@link DoubleConstant}</li>
	 *   <li>{@code LONG_NAME} ("long") - returns a {@link LongConstant}</li>
	 *   <li>{@code INT_NAME} ("int") - returns an {@link IntegerConstant}</li>
	 * </ul>
	 * </p>
	 * <p>
	 * If the input is already a Cast expression, the nested cast is flattened by
	 * casting the innermost expression directly.
	 * </p>
	 *
	 * @param <T>      the target type of the cast
	 * @param type     the Java class representing the target type
	 * @param typeName the string name of the target type
	 * @param value    the expression to cast
	 * @return an optimized expression representing the cast result
	 * @throws ArithmeticException if the value overflows the target type range
	 */
	public static <T> Expression<T> of(Class<T> type, String typeName, Expression<?> value) {
		OptionalDouble d = value.doubleValue();

		if (d.isPresent()) {
			switch (typeName) {
				case Cast.FP_NAME:
					return (Expression) new DoubleConstant(d.getAsDouble());
				case Cast.LONG_NAME:
					if (d.getAsDouble() > Long.MAX_VALUE || d.getAsDouble() < Long.MIN_VALUE) {
						throw new ArithmeticException(String.valueOf(d.getAsDouble()));
					}

					return (Expression) new LongConstant((long) d.getAsDouble());
				case Cast.INT_NAME:
					if (d.getAsDouble() > Integer.MAX_VALUE || d.getAsDouble() < Integer.MIN_VALUE) {
						throw new ArithmeticException(String.valueOf(d.getAsDouble()));
					}

					return (Expression) new IntegerConstant((int) d.getAsDouble());
			}
		}

		if (value instanceof Cast) {
			return Cast.of(type, typeName, value.getChildren().get(0));
		}

		return new Cast<>(type, typeName, value);
	}
}
