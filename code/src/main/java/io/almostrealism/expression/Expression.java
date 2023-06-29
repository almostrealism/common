/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.code.Tree;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// TODO  Make abstract
public class Expression<T> implements Tree<Expression<?>> {
	public static boolean enableSimplification = true;
	public static boolean enableWarnings = SystemUtils.isEnabled("AR_CODE_EXPRESSION_WARNINGS").orElse(true);

	public static Function<Expression<?>, Expression<?>> toDouble = e -> new Cast("double", e);

	private Class<T> type;
	private Supplier<String> expression;
	private List<Variable<?, ?>> dependencies = new ArrayList<>();
	private List<Expression<?>> children = new ArrayList<>();
	private int arraySize = -1;

	public Expression(Class<T> type) {
		setType(type);
	}

	@Deprecated
	public Expression(Class<T> type, String expression) {
		this(type, expression, Collections.emptyList(), new Variable[0]);
		// System.out.println("WARN: Deprecated Expression construction");
	}

	public Expression(Class<T> type, String expression, Expression<?>... children) {
		if (type == null) {
			throw new IllegalArgumentException("Type is required");
		}

		setType(type);
		this.expression = () -> expression;
		this.children = List.of(children);
		this.dependencies = new ArrayList<>();
		this.dependencies.addAll(dependencies(children));
	}

	public Expression(Class<T> type, String expression, Variable<T, ?> referent, Expression<?> argument) {
		if (type == null) {
			throw new IllegalArgumentException("Type is required");
		}

		setType(type);
		this.expression = () -> expression;
		this.children = argument == null ? Collections.emptyList() : List.of(argument);
		this.dependencies = new ArrayList<>();
		this.dependencies.add(referent);
		if (argument != null) this.dependencies.addAll(argument.getDependencies());
	}

	@Deprecated
	public Expression(Class<T> type, String expression, List<Expression<?>> children, Variable<?, ?>... dependencies) {
		if (type == null) {
			throw new IllegalArgumentException("Type is required");
		}

		setType(type);
		this.expression = () -> expression;
		this.children = children;
		this.dependencies = new ArrayList<>();
		this.dependencies.addAll(Arrays.asList(dependencies));

		if (enableWarnings && dependencies.length > 0) {
			System.out.println("WARN: Deprecated Expression construction");
		}
	}

	public Expression(int arraySize) {
		setType(null);
		setExpression((Supplier<String>) null);
		setArraySize(arraySize);
	}

	public void setType(Class<T> t) { this.type = t; }
	public Class<T> getType() { return this.type; }

	public boolean isNull() {
		return expression == null || expression.get() == null;
	}

	public OptionalInt intValue() { return OptionalInt.empty(); }
	public OptionalDouble doubleValue() {
		OptionalInt intValue = intValue();
		return intValue.isPresent() ? OptionalDouble.of(intValue.getAsInt()) : OptionalDouble.empty();
	}

	public String getSimpleExpression() {
		if (!enableSimplification) return getExpression();

		if (getClass() == Expression.class) {
			if (enableWarnings) System.out.println("WARN: Unable to retrieve simplified expression");
			return getExpression();
		}

		Expression<?> simplified = simplify();
		String exp = simplified.getExpression();

		w: while (true) {
			Expression<?> next = simplified.simplify();
			String nextExp = next.getExpression();

			if (nextExp.equals(exp)) {
				break w;
			}

			simplified = next;
			exp = nextExp;
		}

		return exp;
	}

	@Deprecated
	public String getExpression() {
		if (isNull()) return null;
		return expression.get();
	}

	public void setExpression(String expression) { this.expression = () -> expression; }
	public void setExpression(Supplier<String> expression) { this.expression = expression; }

	public List<Variable<?, ?>> getDependencies() { return dependencies; }

	public int getArraySize() { return arraySize; }
	public void setArraySize(int arraySize) { this.arraySize = arraySize; }

	public T getValue() {
		OptionalDouble v = doubleValue();

		if (v.isPresent()) {
			return (T) Double.valueOf(v.getAsDouble());
		} else if (expression != null) {
			return (T) expression.get();
		} else {
			throw new UnsupportedOperationException();
		}
	}

	public Minus minus() { return new Minus((Expression) this); }

	public Sum add(int operand) { return new Sum((Expression) this, (Expression) new IntegerConstant(operand)); }
	public Sum add(Expression<Double> operand) { return new Sum((Expression) this, operand); }
	public Difference subtract(Expression<Double> operand) { return new Difference((Expression) this, operand); }

	public Product multiply(int operand) { return new Product((Expression) this, (Expression) new IntegerConstant(operand)); }
	public Product multiply(Expression<Double> operand) { return new Product((Expression) this, operand); }

	public Quotient divide(int operand) { return new Quotient((Expression) this, (Expression) new IntegerConstant(operand)); }
	public Quotient divide(Expression<Double> operand) { return new Quotient((Expression) this, operand); }

	public Exponent pow(Expression<Double> operand) { return new Exponent((Expression) this, operand); }
	public Exp exp() { return new Exp((Expression) this); }

	public Floor floor() { return new Floor((Expression) this); }
	public Ceiling ceil() { return new Ceiling((Expression) this); }

	public Mod mod(Expression<Double> operand) { return new Mod((Expression) this, operand); }

	public Equals eq(Expression<?> operand) { return new Equals(this, operand); }

	public Expression<?> toDouble() { return toDouble.apply(this); }

	public Cast toInt() { return new Cast("int", this); }

	@Override
	public List<Expression<?>> getChildren() {
		return children;
	}

	public Expression<T> generate(List<Expression<?>> children) {
		throw new UnsupportedOperationException();
	}

	public List<Expression<?>> flatten() { return getChildren(); }

	public Expression<T> simplify() {
		return generate(getChildren().stream().map(Expression::simplify).collect(Collectors.toList()));
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Expression)) return false;

		Expression v = (Expression) obj;
		if (type != v.getType()) return false;
		if (!Objects.equals(expression, v.expression)) return false;
		if (!Objects.equals(dependencies, v.getDependencies())) return false;

		return true;
	}

	@Override
	public int hashCode() { return getValue().hashCode(); }

	private static Set<Variable<?, ?>> dependencies(Expression expressions[]) {
		Set<Variable<?, ?>> dependencies = new HashSet<>();
		for (Expression e : expressions) dependencies.addAll(e.getDependencies());
		return dependencies;
	}
}
