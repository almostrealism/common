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

package io.almostrealism.expression;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.kernel.ArithmeticIndexSequence;
import io.almostrealism.kernel.ArrayIndexSequence;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.KernelTree;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.kernel.SequenceGenerator;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.scope.Variable;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public abstract class Expression<T> implements KernelTree<Expression<?>>, SequenceGenerator, ExpressionFeatures, ConsoleFeatures {
	public static boolean enableKernelSeqCache = false;
	public static boolean enableBatchEvaluation = false;
	public static boolean enableArithmeticSequence = true;
	public static int maxCacheItemSize = 16;
	public static int maxCacheItems = 128;
	public static int maxDepth = 4096;

	public static boolean enableWarnings = SystemUtils.isEnabled("AR_CODE_EXPRESSION_WARNINGS").orElse(true);

	public static Function<Expression<?>, Expression<Double>> toDouble = e -> new Cast<>(Double.class, "double", e);

	private static LanguageOperations lang;
	private static FrequencyCache<String, IndexSequence> kernelSeqCache;

	static {
		lang = new LanguageOperationsStub();

		if (enableKernelSeqCache) {
			kernelSeqCache = new FrequencyCache<>(maxCacheItems, 0.7);
		}
	}

	private Class<T> type;
	private List<Expression<?>> children;

	private int depth;
	private boolean isSimple;
	private boolean isSeriesSimplificationChild;
	private KernelSeriesProvider seriesProvider;

	public Expression(Class<T> type) {
		setType(type);
	}

	public Expression(Class<T> type, Expression<?>... children) {
		if (type == null) {
			throw new IllegalArgumentException("Type is required");
		}

		setType(type);
		this.children = List.of(children);
		this.depth = this.children.stream().mapToInt(e -> e.depth).max().orElse(0) + 1;

		if (depth > maxDepth) {
			throw new UnsupportedOperationException();
		}
	}

	public void setType(Class<T> t) { this.type = t; }
	public Class<T> getType() { return this.type; }

	public boolean isInt() { return getType() == Integer.class; }
	public boolean isFP() { return getType() == Double.class; }

	public boolean isNull() { return false; }
	public boolean isMasked() { return false; }
	public boolean isSingleIndex() { return false; }
	public boolean isSingleIndexMasked() { return isMasked() && getChildren().get(0).isSingleIndex(); }
	public boolean isValue(IndexValues values) { return false; }

	public Optional<Boolean> booleanValue() { return Optional.empty(); }

	public OptionalInt intValue() { return OptionalInt.empty(); }

	public OptionalLong longValue() {
		OptionalInt intValue = intValue();
		return intValue.isPresent() ? OptionalLong.of(intValue.getAsInt()) : OptionalLong.empty();
	}

	public OptionalDouble doubleValue() {
		OptionalInt intValue = intValue();
		return intValue.isPresent() ? OptionalDouble.of(intValue.getAsInt()) : OptionalDouble.empty();
	}

	public Expression<T> withValue(String name, Number value) {
		return generate(getChildren().stream()
				.map(e -> e.withValue(name, value))
				.collect(Collectors.toList()));
	}

	public Expression<T> withIndex(Index index, Expression<?> e) {
		if (!contains(index)) return this;

		if (this instanceof Index && Objects.equals(((Index) this).getName(), index.getName())) {
			return (Expression) e;
		}

		return generate(getChildren().stream()
				.map(c -> c.withIndex(index, e))
				.collect(Collectors.toList()));
	}

	public Expression<T> withIndex(Index index, int value) {
		return withIndex(index, new IntegerConstant(value));
	}

	public Set<Index> getIndices() {
		if (this instanceof Index) return Set.of((Index) this);

		return getChildren().stream()
				.flatMap(e -> e.getIndices().stream())
				.collect(Collectors.toSet());
	}

	public boolean contains(Index idx) {
		if (this instanceof Index && Objects.equals(((Index) this).getName(), idx.getName())) {
			return true;
		} else if (getChildren().isEmpty()) {
			return false;
		}

		return getChildren().stream().anyMatch(e -> e.contains(idx));
	}

	public boolean containsReference(Variable var) {
		return getChildren().stream().anyMatch(e -> e.containsReference(var));
	}

	public KernelSeries kernelSeries() {
		return KernelSeries.infinite();
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalInt i = intValue();
		if (i.isPresent()) return OptionalLong.of(i.getAsInt());

		OptionalDouble d = doubleValue();
		if (d.isPresent()) return OptionalLong.of((long) Math.ceil(d.getAsDouble()));

		return OptionalLong.empty();
	}

	public Number evaluate(Number... children) {
		throw new UnsupportedOperationException();
	}

	public Number[] batchEvaluate(List<Number[]> children, int len) {
		return IntStream.range(0, len).parallel()
				.mapToObj(i -> evaluate(children.stream().map(c -> c[i]).toArray(Number[]::new)))
				.toArray(Number[]::new);
	}

	@Override
	public Number value(IndexValues indexValues) {
		throw new UnsupportedOperationException();
	}

	public IndexSequence sequence() {
		Set<Index> indices = getIndices();
		if (indices.size() != 1) throw new UnsupportedOperationException();

		return sequence(indices.iterator().next(),
				Math.toIntExact(indices.iterator().next().getLimit().getAsLong()), Integer.MAX_VALUE);
	}

	public IndexSequence sequence(int len) {
		Set<Index> indices = getIndices();
		if (indices.size() != 1) throw new UnsupportedOperationException();

		return sequence(indices.iterator().next(), len);
	}

	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		if (len < 0) throw new IllegalArgumentException();

		if (enableArithmeticSequence && equals(index)) {
			return new ArithmeticIndexSequence(1, 1, len);
		}

		if (!isValue(new IndexValues().put(index, 0))) {
			throw new IllegalArgumentException();
		}

		int nodes = countNodes();
		String exp = nodes <= maxCacheItemSize ? getExpression(lang) : null;

		if (kernelSeqCache != null && exp != null) {
			IndexSequence cached = kernelSeqCache.get(exp);
			if (cached != null && cached.lengthLong() >= len) {
				return cached.subset(len);
			}
		}

		Class type = getType();
		if (type == Boolean.class) type = Integer.class;
		if (len > limit) {
			return null;
		}

		IndexSequence seq;

		if (enableBatchEvaluation) {
			seq = ArrayIndexSequence.of(type, batchEvaluate(getChildren().stream()
					.map(e -> e.sequence(index, len, limit).toArray())
					.collect(Collectors.toList()), Math.toIntExact(len)));
		} else {
			seq = ArrayIndexSequence.of(type, IntStream.range(0, Math.toIntExact(len)).parallel()
					.mapToObj(i -> value(new IndexValues().put(index, i))).toArray(Number[]::new));
		}

		cacheSeq(exp, seq);
		return seq;
	}

	public Expression<?> getSimplified() { return getSimplified(new NoOpKernelStructureContext()); }

	public Expression<?> getSimplified(KernelStructureContext context) {
		if (isSimple()) return this;

		if (getClass() == Expression.class) {
			if (enableWarnings) System.out.println("WARN: Unable to retrieve simplified expression");
			return this;
		}

		LanguageOperations lang = new LanguageOperationsStub();
		context = context.asNoOp();

		Expression<?> simplified = simplify(context);
		if (simplified.isSimple()) return simplified;

		String exp = simplified.getExpression(lang);

		w: while (true) {
			Expression<?> next = simplified.simplify(context);
			if (next.isSimple()) return next;

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

	public String getExpressionSummary() {
		if (depth < 10) return getExpression(lang);
		return getClass().getSimpleName() + "<" +
					getType().getSimpleName() +
				">[depth=" + depth + "]";
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

	public List<Variable<?, ?>> getDependencies() {
		return new ArrayList<>(dependencies(getChildren().toArray(new Expression[0])));
	}

	public int getArraySize() { return -1; }

	public T getValue() {
		OptionalInt i = intValue();
		if (i.isPresent()) return (T) (Integer) i.getAsInt();

		OptionalDouble v = doubleValue();
		if (v.isPresent()) return (T) (Double) v.getAsDouble();

		return null;
	}

	public ExpressionAssignment<T> assign(Expression exp) {
		throw new UnsupportedOperationException();
	}

	public Expression minus() { return new Minus(this); }

	public Expression add(int operand) { return Sum.of(this, new IntegerConstant(operand)); }
	public Expression add(Expression<? extends Number> operand) { return Sum.of(this, operand); }
	public Expression subtract(Expression<? extends Number> operand) { return new Difference(this, operand); }

	public Expression multiply(int operand) { return operand == 1 ? this : Product.of(this, new IntegerConstant(operand)); }
	public Expression multiply(Expression<? extends Number> operand) { return Product.of(this, operand); }

	public Expression divide(int operand) { return operand == 1 ? this : Quotient.of(this, new IntegerConstant(operand)); }
	public Expression divide(Expression<?> operand) { return Quotient.of(this, operand); }

	public Expression reciprocal() { return Quotient.of(new DoubleConstant(1.0), this); }

	public Exponent pow(Expression<Double> operand) { return new Exponent((Expression) this, operand); }
	public Exp exp() { return new Exp((Expression) this); }

	public Expression floor() {
		if (getType() == Integer.class) return this;

		OptionalDouble v = doubleValue();
		if (v.isPresent()) return new DoubleConstant(Math.floor(v.getAsDouble()));

		return new Floor((Expression) this);
	}

	public Expression ceil() {
		if (getType() == Integer.class) return this;

		OptionalDouble v = doubleValue();
		if (v.isPresent()) return new DoubleConstant(Math.ceil(v.getAsDouble()));

		return new Ceiling((Expression) this);
	}

	public Expression mod(Expression<Double> operand) { return Mod.of(this, operand); }
	public Expression mod(Expression<?> operand, boolean fp) { return Mod.of(this, operand, fp); }
	public Expression<Integer> imod(Expression<? extends Number> operand) { return mod(operand, false); }
	public Expression<Integer> imod(int operand) { return imod(new IntegerConstant(operand)); }
	public Expression<Integer> imod(long operand) {
		if (operand > Integer.MAX_VALUE) {
			return imod(new LongConstant(operand));
		} else {
			return imod((int) operand);
		}
	}

	public Sine sin() { return new Sine((Expression) this); }
	public Cosine cos() { return new Cosine((Expression) this); }
	public Tangent tan() { return new Tangent((Expression) this); }

	public Negation not() {
		if (getType() != Boolean.class)
			throw new IllegalArgumentException();

		return new Negation((Expression) this);
	}

	public Equals eq(double operand) { return new Equals(this, new DoubleConstant(operand)); };
	public Equals eq(Expression<?> operand) { return new Equals(this, operand); };
	public Conjunction and(Expression<Boolean> operand) { return new Conjunction((Expression) this, operand); };
	public Expression conditional(Expression<?> positive, Expression<?> negative) {
		if (getType() != Boolean.class) throw new IllegalArgumentException();
		return Conditional.of((Expression<Boolean>) this, (Expression) positive, (Expression) negative);
	}
	public Greater greaterThan(Expression<?> operand) { return new Greater(this, operand); };
	public Greater greaterThanOrEqual(Expression<?> operand) { return new Greater(this, operand, true); };
	public Less lessThan(Expression<?> operand) { return new Less(this, operand); };
	public Less lessThanOrEqual(Expression<?> operand) { return new Less(this, operand, true); };

	public Expression<Double> toDouble() {
		if (getType() == Double.class) return (Expression<Double>) this;
		return toDouble.apply(this);
	}

	public Expression<Integer> toInt() {
		return toInt(false);
	}

	public Expression<Integer> toInt(boolean requireInt) {
		boolean cast = requireInt ? getType() != Integer.class : isFP();
		if (getType() == Integer.class) return (Expression<Integer>) this;
		return cast ? new Cast(Integer.class, "int", this) : (Expression<Integer>) this;
	}

	public CollectionExpression delta(CollectionExpression target) {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<Expression<?>> getChildren() {
		return children == null ? Collections.emptyList() : children;
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		throw new UnsupportedOperationException();
	}

	protected Expression<T> populate(Expression<?> oldExpression) {
		if (oldExpression.isSimple) this.isSimple = true;
		if (oldExpression.isSeriesSimplificationChild) this.isSeriesSimplificationChild = true;
		if (oldExpression.seriesProvider != null) this.seriesProvider = oldExpression.seriesProvider;
		return this;
	}

	public boolean isSimple() { return isSimple || getChildren().isEmpty(); }

	public List<Expression<?>> flatten() { return getChildren(); }

	@Override
	public Expression<T> simplify(KernelStructureContext context) {
		KernelSeriesProvider provider = context.getSeriesProvider();

		if ((provider == null && isSimple()) || (provider != null && provider == seriesProvider)) {
			return this;
		} else if (provider == null || isSeriesSimplificationChild || !isSeriesSimplificationTarget()) {
			return generate(getChildren().stream()
					.map((Expression<?> expression) -> expression.simplify(context))
					.collect(Collectors.toList())).populate(this);
		}

		Expression<?> simplified[] = new Expression[getChildren().size()];

		i: for (int i = 0; i < simplified.length; i++) {
			simplified[i] = children.get(i);
			simplified[i] = simplified[i].simplify(context);
			if (simplified[i] instanceof Index || simplified[i] instanceof Constant) continue i;

			Set<Index> indices = simplified[i].getIndices();
			Index target = null;

			if (!indices.isEmpty()) {
				target = indices.stream().filter(idx -> idx instanceof KernelIndex).findFirst()
						.orElse(indices.stream().findFirst().orElse(null));
			}

			if (target == null || simplified[i].isValue(new IndexValues().put(target, 0))) {
				simplified[i] = provider.getSeries(simplified[i]).getSimplified(context);
				simplified[i].children().forEach(c -> c.isSeriesSimplificationChild = true);
			}
		}

		Expression simple = generate(List.of(simplified)).populate(this);
		simple.seriesProvider = provider;
		return simple;
	}

	public boolean isSeriesSimplificationTarget() { return true; }

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Expression)) return false;

		Expression v = (Expression) obj;
		if (type != v.getType()) return false;

		LanguageOperationsStub lang = new LanguageOperationsStub();
		if (!Objects.equals(getExpression(lang), v.getExpression(lang))) return false;
		if (!Objects.equals(getDependencies(), v.getDependencies())) return false;

		return true;
	}

	@Override
	public int hashCode() { return isNull() ? 0 : getExpression(new LanguageOperationsStub()).hashCode(); }

	private static void cacheSeq(String exp, IndexSequence seq) {
		if (kernelSeqCache != null && exp != null) {
			kernelSeqCache.put(exp, seq);
		}
	}

	private static Set<Variable<?, ?>> dependencies(Expression expressions[]) {
		Set<Variable<?, ?>> dependencies = new HashSet<>();
		for (Expression e : expressions) dependencies.addAll(e.getDependencies());
		return dependencies;
	}
}
