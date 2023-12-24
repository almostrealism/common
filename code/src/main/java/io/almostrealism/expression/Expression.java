/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelTree;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.relation.Tree;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.DistributionMetric;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.io.TimingMetric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public abstract class Expression<T> implements KernelTree<Expression<?>>, ConsoleFeatures {
	public static boolean enableSimplification = true;
	public static boolean enableWarnings = SystemUtils.isEnabled("AR_CODE_EXPRESSION_WARNINGS").orElse(true);

	public static DistributionMetric distribution = Scope.console.distribution("expressionCounts");
	public static TimingMetric timing = Scope.console.timing("expression");

	public static Function<Expression<?>, Expression<Double>> toDouble = e -> new Cast<>(Double.class, "double", e);

	private Class<T> type;
	private List<Variable<?, ?>> dependencies = new ArrayList<>();
	private List<Expression<?>> children = new ArrayList<>();

	private boolean isSimple;
	private boolean isSeriesSimplificationChild;
	private KernelSeriesProvider seriesProvider;
	private Number[] latestKernelSeq;

	public Expression(Class<T> type) {
		setType(type);
	}

	public Expression(Class<T> type, Expression<?>... children) {
		if (type == null) {
			throw new IllegalArgumentException("Type is required");
		}

		setType(type);
		this.children = List.of(children);
		this.dependencies = new ArrayList<>();
		this.dependencies.addAll(dependencies(children));
	}

	public Expression(Class<T> type, Variable<T, ?> referent, Expression<?> argument) {
		if (type == null) {
			throw new IllegalArgumentException("Type is required");
		}

		setType(type);
		this.children = argument == null ? Collections.emptyList() : List.of(argument);
		this.dependencies = new ArrayList<>();
		this.dependencies.add(referent);
		if (argument != null) this.dependencies.addAll(argument.getDependencies());
	}

	public void setType(Class<T> t) { this.type = t; }
	public Class<T> getType() { return this.type; }

	public boolean isNull() { return false; }

	public Optional<Boolean> booleanValue() { return Optional.empty(); }

	public OptionalInt intValue() { return OptionalInt.empty(); }
	public OptionalDouble doubleValue() {
		OptionalInt intValue = intValue();
		return intValue.isPresent() ? OptionalDouble.of(intValue.getAsInt()) : OptionalDouble.empty();
	}

	public boolean isKernelValue() { return false; }

	public KernelSeries kernelSeries() {
		return KernelSeries.infinite();
	}

	public List<Number> getDistinctKernelValues(int kernelMax) {
		if (!isKernelValue()) return null;

		return Arrays.stream(kernelSeq(kernelMax)).distinct().collect(Collectors.toList());
	}

	public OptionalInt upperBound() {
		OptionalInt i = intValue();
		if (i.isPresent()) return i;

		OptionalDouble d = doubleValue();
		if (d.isPresent()) return OptionalInt.of((int) Math.ceil(d.getAsDouble()));

		return OptionalInt.empty();
	}

	public Number kernelValue(int kernelIndex) {
		throw new UnsupportedOperationException();
	}

	public Number[] kernelSeq(int len) {
		long start = System.nanoTime();

		try {
			if (latestKernelSeq != null && latestKernelSeq.length >= len) {
				return Arrays.copyOf(latestKernelSeq, len);
			} else {
				Number seq[] = IntStream.range(0, len).parallel().mapToObj(this::kernelValue).toArray(Number[]::new);
				latestKernelSeq = seq;
				return seq;
			}
		} finally {
			timing.addEntry("kernelSeq", System.nanoTime() - start);
		}
	}

	public int[] booleanSeq(int len) { return null; }

	public Expression<?> getSimplified() {
		if (!enableSimplification || isSimple()) return this;

		if (getClass() == Expression.class) {
			if (enableWarnings) System.out.println("WARN: Unable to retrieve simplified expression");
			return this;
		}

		LanguageOperations lang = new LanguageOperationsStub();

		Expression<?> simplified = simplify(null);
		String exp = simplified.getExpression(lang);

		w: while (true) {
			Expression<?> next = simplified.simplify(null);
			String nextExp = next.getExpression(lang);

			if (nextExp.equals(exp)) {
				break w;
			}

			simplified = next;
			exp = nextExp;
		}

		simplified.isSimple = true;
		return simplified;
	}

	public String getSimpleExpression(LanguageOperations lang) {
		return Optional.ofNullable(getSimplified())
				.map(e -> e.getExpression(lang)).orElse(null);
	}

	public abstract String getExpression(LanguageOperations lang);

	public String getWrappedExpression(LanguageOperations lang) {
		return "(" + getExpression(lang) + ")";
	}

	public List<Expression> find(String text) {
		List<Expression> found = new ArrayList<>();
		for (Expression e : getChildren()) {
			found.addAll(e.find(text));
		}

		if (found.isEmpty() && getExpression(new LanguageOperationsStub()).contains(text)) {
			found.add(this);
		}

		return found;
	}

	public List<Variable<?, ?>> getDependencies() { return dependencies; }

	public int getArraySize() { return -1; }

	public T getValue() {
		OptionalInt i = intValue();
		if (i.isPresent()) return (T) (Integer) i.getAsInt();

		OptionalDouble v = doubleValue();
		if (v.isPresent()) return (T) (Double) v.getAsDouble();

		return null;
	}
	public Variable assign(Expression exp) {
		// return new Variable(getSimpleExpression(), false, exp);
		throw new UnsupportedOperationException();
	}

	public Minus minus() { return new Minus((Expression) this); }

	public Sum add(int operand) { return new Sum((Expression) this, (Expression) new IntegerConstant(operand)); }
	public Sum add(Expression<Double> operand) { return new Sum((Expression) this, operand); }
	public Difference subtract(Expression<Double> operand) { return new Difference((Expression) this, operand); }

	public Product multiply(int operand) { return new Product((Expression) this, (Expression) new IntegerConstant(operand)); }
	public Product multiply(Expression<? extends Number> operand) { return new Product((Expression) this, (Expression) operand); }

	public Quotient divide(int operand) { return new Quotient((Expression) this, (Expression) new IntegerConstant(operand)); }
	public Quotient divide(Expression<?> operand) { return new Quotient((Expression) this, (Expression) operand); }

	public Quotient reciprocal() { return new Quotient(new DoubleConstant(1.0), (Expression) this); }

	public Exponent pow(Expression<Double> operand) { return new Exponent((Expression) this, operand); }
	public Exp exp() { return new Exp((Expression) this); }

	public Expression floor() {
		if (getType() == Integer.class) return this;
		return new Floor((Expression) this);
	}

	public Expression ceil() {
		if (getType() == Integer.class) return this;
		return new Ceiling((Expression) this);
	}

	public Mod mod(Expression<Double> operand) { return new Mod((Expression) this, operand); }
	public Mod mod(Expression<?> operand, boolean fp) { return new Mod((Expression) this, (Expression) operand, fp); }
	public Mod<Integer> imod(Expression<Integer> operand) { return mod(operand, false); }
	public Mod<Integer> imod(int operand) { return imod(new IntegerConstant(operand)); }

	public Sine sin() { return new Sine((Expression) this); }
	public Cosine cos() { return new Cosine((Expression) this); }
	public Tangent tan() { return new Tangent((Expression) this); }

	public Equals eq(Expression<?> operand) { return new Equals(this, operand); };
	public Conjunction and(Expression<Boolean> operand) { return new Conjunction((Expression) this, operand); };
	public Greater greaterThan(Expression<?> operand) { return new Greater(this, operand); };
	public Greater greaterThanOrEqual(Expression<?> operand) { return new Greater(this, operand, true); };
	public Less lessThan(Expression<?> operand) { return new Less(this, operand); };
	public Less lessThanOrEqual(Expression<?> operand) { return new Less(this, operand, true); };

	public Expression<Double> toDouble() {
		if (getType() == Double.class) return (Expression<Double>) this;
		return toDouble.apply(this);
	}

	public Expression<Integer> toInt() {
		if (getType() == Integer.class) return (Expression<Integer>) this;
		return new Cast(Integer.class, "int", this);
	}

	public CollectionExpression delta(TraversalPolicy shape, Function<Expression, Predicate<Expression>> target) {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<Expression<?>> getChildren() {
		return children;
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		throw new UnsupportedOperationException();
	}

	protected Expression<T> populate(Expression<?> oldExpression) {
		this.latestKernelSeq = oldExpression.latestKernelSeq;
		if (oldExpression.isSimple) this.isSimple = true;
		if (oldExpression.isSeriesSimplificationChild) this.isSeriesSimplificationChild = true;
		if (oldExpression.seriesProvider != null) this.seriesProvider = oldExpression.seriesProvider;
		return this;
	}

	public boolean isSimple() { return isSimple || getChildren().isEmpty(); }

	public List<Expression<?>> flatten() { return getChildren(); }

	@Override
	public Expression<T> simplify(KernelSeriesProvider provider) {
		if ((provider == null && isSimple()) || (provider != null && provider == seriesProvider)) {
			return this;
		} else if (provider == null || isSeriesSimplificationChild || !isSeriesSimplificationTarget()) {
			return generate(getChildren().stream()
					.map((Expression<?> expression) -> expression.simplify(provider))
					.collect(Collectors.toList())).populate(this);
		}

		Expression<?> simplified[] = new Expression[children.size()];

		for (int i = 0; i < simplified.length; i++) {
			simplified[i] = children.get(i).getSimplified();

			if (simplified[i].isKernelValue()) {
				simplified[i] = provider.getSeries(simplified[i]).getSimplified();
				simplified[i].children().forEach(c -> c.isSeriesSimplificationChild = true);
			}
		}

		Expression simple = generate(List.of(simplified)).populate(this);
		simple.seriesProvider = provider;
		return simple;
	}

	public boolean isSeriesSimplificationTarget() { return getType() == Boolean.class; }

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Expression)) return false;

		Expression v = (Expression) obj;
		if (type != v.getType()) return false;

		LanguageOperationsStub lang = new LanguageOperationsStub();
		if (!Objects.equals(getExpression(lang), v.getExpression(lang))) return false;
		if (!Objects.equals(dependencies, v.getDependencies())) return false;

		return true;
	}

	@Override
	public int hashCode() { return isNull() ? 0 : getExpression(new LanguageOperationsStub()).hashCode(); }

	private static Set<Variable<?, ?>> dependencies(Expression expressions[]) {
		Set<Variable<?, ?>> dependencies = new HashSet<>();
		for (Expression e : expressions) dependencies.addAll(e.getDependencies());
		return dependencies;
	}
}
