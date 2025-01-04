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
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.scope.ExpressionCache;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.scope.Variable;
import io.almostrealism.uml.Signature;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.io.Bits;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public abstract class Expression<T> implements
		KernelTree<Expression<?>>, SequenceGenerator, Signature,
		ExpressionFeatures, ConsoleFeatures {

	public static ScopeTimingListener timing;
	protected static LanguageOperations lang;
	private static FrequencyCache<String, IndexSequence> kernelSeqCache;

	static {
		lang = new LanguageOperationsStub();

		if (ScopeSettings.enableKernelSeqCache) {
			kernelSeqCache = new FrequencyCache<>(ScopeSettings.maxCacheItems, 0.7);
		}
	}

	private Class<T> type;
	private List<Expression<?>> children;
	private int depth, nodeCount;
	private short hash;

	private Set<Index> indices;

	private boolean containsLong;
	private boolean isSimple;
	private boolean isSeriesSimplificationChild;
	private KernelSeriesProvider seriesProvider;

	public Expression(Class<T> type) {
		this.type = type;
	}

	public Expression(Class<T> type, Expression<?>... children) {
		this(type, true, children);
	}

	protected Expression(Class<T> type, boolean init, Expression<?>... children) {
		if (type == null) {
			throw new IllegalArgumentException("Type is required");
		}

		this.type = type;
		this.children = List.of(children);
		if (init) init();
	}

	protected void init() {
		ScopeSettings.reviewChildren(getChildren());

		this.depth = getChildren().stream().mapToInt(e -> e.depth).max().orElse(-1) + 1;

		long c = getChildren().stream().mapToLong(e -> e.nodeCount).sum();

		if (type == null) {
			throw new ExpressionException("Expression requires a type", depth, c);
		} else if (c >= Integer.MAX_VALUE) {
			throw new ExpressionException("Expression too large", depth, c);
		} else {
			this.nodeCount = Math.toIntExact(c + 1);
		}

		this.containsLong = (getType() == Long.class ||
				getChildren().stream().anyMatch(e -> e.containsLong))
				&& intValue().isEmpty();

		if (getChildren().isEmpty()) {
			hash = (short) (Math.abs(longValue().orElse(1)) % Short.MAX_VALUE);
		} else {
			hash = (short) getChildren().stream().mapToInt(e -> e.hash).reduce(1, (a, b) -> (a % 2713) * (b % 2713));
		}

		if (depth > ScopeSettings.maxDepth) {
			throw new ExpressionException("Expression too deep", depth, nodeCount);
		}
	}

	public Class<T> getType() { return this.type; }

	@Override
	public int treeDepth() { return depth; }

	@Override
	public int countNodes() { return nodeCount; }

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

	public Expression<T> replace(Expression target, Expression replacement) {
		if (this.equals(target)) return replacement;

		return generate(getChildren().stream()
				.map(e -> e.replace(target, replacement))
				.collect(Collectors.toList()));
	}

	public Expression<T> withIndex(Index index, Expression<?> e) {
		if (!containsIndex(index)) return this;

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
		if (indices != null) return indices;
		if (getChildren().isEmpty()) return Collections.emptySet();

		for (Expression<?> e : getChildren()) {
			Set<Index> indices = e.getIndices();

			if (this.indices == null || this.indices.isEmpty()) {
				this.indices = indices;
			} else if (!indices.isEmpty() && !Objects.equals(this.indices, indices)) {
				this.indices = new HashSet<>(this.indices);
				this.indices.addAll(indices);
			}
		}

		return indices;
	}

	public KernelStructureContext getStructureContext() {
		return getChildren().stream()
				.map(Expression::getStructureContext)
				.filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	public boolean containsLong() { return containsLong; }

	public boolean contains(Expression e) {
		if (this.equals(e)) return true;
		if (getChildren().isEmpty()) return false;
		return getChildren().stream().anyMatch(c -> c.contains(e));
	}

	public boolean containsIndex(Index idx) {
		if (this instanceof Index && Objects.equals(((Index) this).getName(), idx.getName())) {
			return true;
		} else if (getChildren().isEmpty()) {
			return false;
		}

		return getChildren().stream().anyMatch(e -> e.containsIndex(idx));
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

	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		OptionalInt i = intValue();
		if (i.isPresent()) return OptionalLong.of(i.getAsInt());

		OptionalDouble d = doubleValue();
		if (d.isPresent()) return OptionalLong.of((long) Math.floor(d.getAsDouble()));

		return OptionalLong.empty();
	}

	public boolean isPossiblyNegative() {
		return lowerBound(null).orElse(-1) < 0;
	}

	public Optional<Boolean> isMultiple(Expression<?> e) {
		if (intValue().isPresent() && e.intValue().isPresent()) {
			return Optional.of(intValue().getAsInt() % e.intValue().getAsInt() == 0);
		}

		return Optional.empty();
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
		return evaluate(getChildren().stream().map(e -> e.value(indexValues)).toArray(Number[]::new));
	}

	public IndexSequence sequence() {
		Set<Index> indices = getIndices();
		if (indices.size() > 1) throw new UnsupportedOperationException();

		if (indices.isEmpty()) {
			return sequence(null, 1, Integer.MAX_VALUE);
		}

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

		if (ScopeSettings.enableArithmeticSequence && equals(index)) {
			return new ArithmeticIndexSequence(1, 1, len);
		}

		if (!isValue(new IndexValues().put(index, 0))) {
			throw new IllegalArgumentException();
		}

		int nodes = countNodes();
		String exp = nodes <= ScopeSettings.maxCacheItemSize ? getExpression(lang) : null;

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

		if (ScopeSettings.enableBatchEvaluation) {
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
		return getSimplified(context, 0);
	}

	public Expression<?> getSimplified(KernelStructureContext context, int depth) {
		if (isSimple(context)) return this;

		if (getClass() == Expression.class) {
			if (ScopeSettings.enableExpressionWarnings)
				System.out.println("WARN: Unable to retrieve simplified expression");
			return this;
		}

		// context = context.asNoOp();

		Expression<?> simplified = simplify(context, depth);
		if (simplified.isSimple(context)) return simplified;

		int hashCode = simplified.hashCode();

		w: while (true) {
			Expression<?> next = simplified.simplify(context);
			if (next.isSimple(context)) return next;

			int nextExp = next.hashCode();

			if (nextExp == hashCode) {
				break w;
			}

			simplified = next;
			hashCode = nextExp;
		}

		if (context == null || context.getKernelMaximum().isEmpty())
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

	public T getValue() {
		OptionalInt i = intValue();
		if (i.isPresent()) return (T) (Integer) i.getAsInt();

		OptionalDouble v = doubleValue();
		if (v.isPresent()) return (T) (Double) v.getAsDouble();

		Optional<Boolean> b = booleanValue();
		if (b.isPresent()) return (T) b.get();

		return null;
	}

	public ExpressionAssignment<T> assign(Expression exp) {
		throw new UnsupportedOperationException();
	}

	public Expression minus() { return Minus.of(this); }

	public Expression<? extends Number> add(int operand) { return Sum.of(this, new IntegerConstant(operand)); }
	public Expression<? extends Number> add(Expression<?> operand) { return Sum.of(this, operand); }
	public Expression<? extends Number> subtract(Expression<? extends Number> operand) { return Difference.of(this, operand); }
	public Expression<? extends Number> subtract(int operand) { return Difference.of(this, new IntegerConstant(operand)); }

	public Expression<? extends Number> multiply(int operand) {
		return operand == 1 ? (Expression) this : multiply(new IntegerConstant(operand));
	}
	public Expression<? extends Number> multiply(long operand) {
		return operand == 1.0 ? (Expression) this : multiply(ExpressionFeatures.getInstance().e(operand));
	}
	public Expression<? extends Number> multiply(double operand) {
		return operand == 1.0 ? (Expression) this : multiply(Constant.of(operand));
	}
	public Expression<? extends Number> multiply(Expression<?> operand) {
		return (Expression) Product.of(this, operand);
	}

	public Expression<? extends Number> divide(int operand) {
		return operand == 1 ? (Expression) this : divide(new IntegerConstant(operand));
	}
	public Expression<? extends Number> divide(long operand) {
		return operand == 1 ? (Expression) this : divide(ExpressionFeatures.getInstance().e(operand));
	}
	public Expression<? extends Number> divide(double operand) {
		return operand == 1.0 ? (Expression) this : divide(Constant.of(operand));
	}
	public Expression<? extends Number> divide(Expression<?> operand) {
		return (Expression)Quotient.of(this, operand);
	}

	public Expression<? extends Number> reciprocal() { return (Expression) Quotient.of(new DoubleConstant(1.0), this); }

	public Expression<Double> pow(Expression<Double> operand) { return Exponent.of((Expression) this, operand); }
	public Expression<Double> exp() { return Exp.of(this); }
	public Expression<Double> log() { return Logarithm.of(this); }

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
	public Tangent tanh() { return new Tangent((Expression) this, true); }

	public Negation not() {
		if (getType() != Boolean.class)
			throw new IllegalArgumentException();

		return new Negation((Expression) this);
	}

	public Expression eqZero() { return eq(0.0); }
	public Expression eq(int operand) { return eq(new IntegerConstant(operand)); };
	public Expression eq(long operand) { return eq(ExpressionFeatures.getInstance().e(operand)); };
	public Expression eq(double operand) { return eq(new DoubleConstant(operand)); };
	public Expression eq(Expression<?> operand) { return Equals.of(this, operand); };
	public Conjunction and(Expression<Boolean> operand) { return new Conjunction((Expression) this, operand); };
	public Expression conditional(Expression<?> positive, Expression<?> negative) {
		if (getType() != Boolean.class) throw new IllegalArgumentException();
		return Conditional.of((Expression<Boolean>) this, positive, negative);
	}

	public Expression<Boolean> greaterThan(Expression<?> operand) { return Greater.of(this, operand); };
	public Expression<Boolean> greaterThanOrEqual(Expression<?> operand) { return Greater.of(this, operand, true); };
	public Expression<Boolean> greaterThanOrEqual(int operand) { return Greater.of(this, new IntegerConstant(operand), true); };

	public Expression<Boolean> lessThan(Expression<?> operand) { return Less.of(this, operand); };
	public Expression<Boolean> lessThan(int operand) { return Less.of(this, new IntegerConstant(operand)); };
	public Expression<Boolean> lessThanOrEqual(Expression<?> operand) { return Less.of(this, operand, true); };

	public Expression<Double> toDouble() {
		if (getType() == Double.class) return (Expression<Double>) this;
		return LanguageOperations.toDouble.apply(this);
	}

	public Expression<Integer> toInt() {
		return toInt(false);
	}

	public Expression<Integer> toInt(boolean requireInt) {
		boolean cast = requireInt ? getType() != Integer.class : isFP();
		if (getType() == Integer.class) return (Expression<Integer>) this;
		return cast ? new Cast(Integer.class, "int", this) : (Expression<Integer>) this;
	}

	public CollectionExpression<?> delta(CollectionExpression<?> target) {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<Expression<?>> getChildren() {
		return children == null ? Collections.emptyList() : children;
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		boolean identical = children.size() == getChildren().size() &&
				IntStream.range(0, children.size())
						.map(i -> children.get(i) == getChildren().get(i) ? 1 : 0)
						.sum() == children.size();
		return identical ? this : recreate(children);
	}

	protected Expression<T> recreate(List<Expression<?>> children) {
		throw new UnsupportedOperationException();
	}

	protected Expression<T> populate(Expression<?> oldExpression) {
		if (oldExpression.isSimple) this.isSimple = true;
		if (oldExpression.isSeriesSimplificationChild) this.isSeriesSimplificationChild = true;
		if (oldExpression.seriesProvider != null) this.seriesProvider = oldExpression.seriesProvider;
		return this;
	}

	public boolean isSimple() { return isSimple || getChildren().isEmpty(); }

	public boolean isSimple(KernelStructureContext ctx) {
		if (!isSimple()) return false;
		if (ctx == null) return true;
		if (seriesProvider != null && seriesProvider == ctx.getSeriesProvider()) return true;
		return ctx.getKernelMaximum().isEmpty();
	}

	public List<Expression<?>> flatten() { return getChildren(); }

	public Expression<?> simplify() {
		KernelStructureContext ctx = getStructureContext();
		if (ctx == null) ctx = new NoOpKernelStructureContext();
		return simplify(ctx);
	}

	@Override
	public Expression<?> simplify(KernelStructureContext context) {
		return ScopeSettings.reviewSimplification(this,
				KernelTree.super.simplify(context));
	}

	@Override
	public Expression<T> simplify(KernelStructureContext context, int depth) {
		KernelSeriesProvider provider = context.getSeriesProvider();

		if ((provider == null && isSimple(context)) || (provider != null && provider == seriesProvider)) {
			return this;
		}

		boolean altered = false;
		Expression<?> simplified[] = new Expression[getChildren().size()];

		i: for (int i = 0; i < simplified.length; i++) {
			try {
				simplified[i] = children.get(i);
				simplified[i] = simplified[i].simplify(context, depth + 1);

				if (provider == null || simplified[i].isSeriesSimplificationChild || !simplified[i].isSeriesSimplificationTarget(depth)) {
					continue i;
				}

				if (simplified[i] instanceof Index || simplified[i] instanceof Constant) continue i;

				Set<Index> indices = simplified[i].getIndices();
				Index target = null;

				if (!indices.isEmpty()) {
					target = indices.stream().filter(idx -> idx instanceof KernelIndex).findFirst()
							.orElse(indices.stream().findFirst().orElse(null));
				}

				IndexValues v = new IndexValues();
				if (target != null) v.put(target, 0);

				simplified[i] = ScopeSettings.reviewSimplification(children.get(i), simplified[i]);

				if (simplified[i].isValue(v)) {
					simplified[i] = ScopeSettings.reviewSimplification(simplified[i],
								provider.getSeries(simplified[i]));

					if (ScopeSettings.isDeepSimplification())
						simplified[i] = simplified[i].getSimplified(context);

					simplified[i].children().forEach(c -> c.isSeriesSimplificationChild = true);
				}
			} finally {
				altered = altered || simplified[i] != children.get(i);
			}
		}

		if (altered) {
			Expression simple = generate(List.of(simplified)).populate(this);
			simple.seriesProvider = provider;
			return simple;
		} else {
			return this;
		}
	}

	public boolean isSeriesSimplificationTarget(int depth) {
		return ScopeSettings.isSeriesSimplificationTarget(this, depth);
	}

	public boolean compare(Expression e) {
		if (this == e) return true;

		if (type != e.getType()) return false;
		if (!Objects.equals(getClass(), e.getClass())) return false;
		if (!Objects.equals(treeDepth(), e.treeDepth())) return false;
		if (!Objects.equals(countNodes(), e.countNodes())) return false;
		if (!Objects.equals(hash, e.hash)) return false;

		if (getChildren().size() != e.getChildren().size()) return false;
		if (IntStream.range(0, getChildren().size())
				.anyMatch(i -> !Objects.equals(getChildren().get(i), e.getChildren().get(i)))) {
			return false;
		}

		return true;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Expression)) return false;

		return timing == null ? compare((Expression) obj) :
				timing.recordDuration("expressionEquals", () -> compare((Expression) obj));
	}

	@Override
	public String signature() {
		return timing == null ? getExpression(lang) :
				timing.recordDuration("expressionSignature", () -> getExpression(lang));
	}

	@Override
	public int hashCode() {
		return timing == null ? hash() : timing.recordDuration("expressionHashCode", this::hash);
	}

	private int hash() {
		return Bits.put(0, 16, hash) +
				Bits.put(16, 10, nodeCount) +
				Bits.put(26, 4, depth) +
				Bits.put(30, 2, getChildren().size());
	}

	public static <T> Expression<T> process(Expression<T> e) {
		int nodes = e.nodeCount;

		if (e.countNodes() > ScopeSettings.maxNodeCount) {
			e = (Expression<T>) e.simplify();

			if (nodes == e.countNodes()) {
				throw new ExpressionException(
						"Large expression not improved by simplification",
						e.treeDepth(), e.countNodes());
			}
		}

		return ExpressionCache.match(e);
	}

	public static Comparator<? super Expression> depthOrder() {
		return (a, b) -> {
			int aDepth = a.treeDepth();
			int bDepth = b.treeDepth();
			if (aDepth == bDepth) return 0;
			return aDepth < bDepth ? 1 : -1;
		};
	}

	public static Number adjustType(Class<? extends Number> type, Number value) {
		boolean fp = type == Double.class;

		if (fp) {
			return value.doubleValue();
		} else {
			long l = value.longValue();

			if (type == Integer.class && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
				return (int) l;
			} else {
				return l;
			}
		}
	}

	public static Expression[] sort(Expression... expressions) {
		Expression result[] = IntStream.range(0, expressions.length)
				.mapToObj(i -> expressions[i])
				.sorted(depthOrder()).toArray(Expression[]::new);

		if (result.length != expressions.length) {
			throw new UnsupportedOperationException();
		}

		return result;
	}

	public static LanguageOperations defaultLanguage() {
		return lang;
	}

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
