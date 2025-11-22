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

/**
 * Abstract base class for all expressions in the code generation system.
 *
 * <p>The {@code Expression} class implements a tree-based representation of mathematical
 * and logical expressions used for generating code across multiple target languages
 * (C, OpenCL, Metal, etc.). Each expression node maintains references to its children,
 * enabling recursive traversal and transformation of the expression tree.</p>
 *
 * <h2>Expression Tree Pattern</h2>
 * <p>Expressions form a directed acyclic graph (DAG) where:</p>
 * <ul>
 *   <li><b>Leaf nodes</b>: Constants ({@link IntegerConstant}, {@link DoubleConstant}),
 *       variables ({@link Variable}), and indices ({@link Index})</li>
 *   <li><b>Internal nodes</b>: Operations like {@link Sum}, {@link Product}, {@link Quotient},
 *       {@link Conditional}, etc.</li>
 *   <li><b>Children</b>: Operands of each operation, stored as an immutable list</li>
 * </ul>
 *
 * <h2>Type System</h2>
 * <p>Each expression is parameterized with a type {@code T} that represents the
 * result type of evaluating the expression. Common types include:</p>
 * <ul>
 *   <li>{@link Integer} - 32-bit integer values</li>
 *   <li>{@link Long} - 64-bit integer values</li>
 *   <li>{@link Double} - 64-bit floating-point values</li>
 *   <li>{@link Boolean} - logical values</li>
 * </ul>
 *
 * <h2>Key Operations</h2>
 * <ul>
 *   <li><b>Code Generation</b>: {@link #getExpression(LanguageOperations)} renders the
 *       expression to target language code</li>
 *   <li><b>Simplification</b>: {@link #simplify(KernelStructureContext)} reduces expression
 *       complexity through algebraic transformations</li>
 *   <li><b>Evaluation</b>: {@link #value(IndexValues)} computes the concrete value given
 *       index assignments</li>
 *   <li><b>Transformation</b>: Methods like {@link #withIndex(Index, Expression)} and
 *       {@link #replace(Expression, Expression)} create modified expression trees</li>
 * </ul>
 *
 * <h2>Tree Metrics</h2>
 * <p>Each expression tracks structural metrics for optimization decisions:</p>
 * <ul>
 *   <li>{@link #treeDepth()} - Maximum path length to any leaf node</li>
 *   <li>{@link #countNodes()} - Total number of nodes in the subtree</li>
 *   <li>Hash values for efficient equality checking and caching</li>
 * </ul>
 *
 * <h2>Arithmetic Operations</h2>
 * <p>The class provides fluent methods for constructing expressions:</p>
 * <pre>{@code
 * Expression<?> x = new IntegerConstant(5);
 * Expression<?> result = x.add(3).multiply(2).divide(4);  // ((5 + 3) * 2) / 4
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Expression trees are effectively immutable after construction. Transformation
 * operations return new expression instances rather than modifying existing ones.
 * The static fields ({@link #timing}, {@link #lang}) are shared across threads.</p>
 *
 * @param <T> the result type of this expression when evaluated
 *
 * @see KernelTree
 * @see SequenceGenerator
 * @see ExpressionFeatures
 *
 * @author Michael Murray
 */
public abstract class Expression<T> implements
		KernelTree<Expression<?>>, SequenceGenerator, Signature,
		ExpressionFeatures, ConsoleFeatures {

	/**
	 * Optional listener for recording timing metrics of expression operations.
	 * When non-null, operations like {@link #equals(Object)}, {@link #hashCode()},
	 * and {@link #signature()} will record their execution duration.
	 */
	public static ScopeTimingListener timing;

	/**
	 * Default language operations used for expression rendering when no specific
	 * language context is provided. Initialized to a {@link LanguageOperationsStub}
	 * which provides basic language-agnostic rendering.
	 */
	protected static LanguageOperations lang;

	/**
	 * Cache for storing computed index sequences to avoid redundant calculations.
	 * Enabled when {@link ScopeSettings#enableKernelSeqCache} is true. The cache
	 * uses frequency-based eviction with a maximum of {@link ScopeSettings#maxCacheItems}
	 * entries.
	 */
	private static FrequencyCache<String, IndexSequence> kernelSeqCache;

	static {
		lang = new LanguageOperationsStub();

		if (ScopeSettings.enableKernelSeqCache) {
			kernelSeqCache = new FrequencyCache<>(ScopeSettings.maxCacheItems, 0.7);
		}
	}

	/** The result type class for this expression (e.g., Integer.class, Double.class). */
	private Class<T> type;

	/** Immutable list of child expressions representing operands of this expression. */
	private List<Expression<?>> children;

	/** Maximum depth from this node to any leaf in the subtree. */
	private int depth;

	/** Total count of nodes in the expression subtree rooted at this node. */
	private int nodeCount;

	/** Compact hash value computed from children for efficient equality checks. */
	private short hash;

	/** Cached set of all {@link Index} instances referenced in this expression subtree. */
	private Set<Index> indices;

	/** Flag indicating whether this expression or any descendant has type {@link Long}. */
	private boolean containsLong;

	/** Flag indicating this expression has been fully simplified and needs no further processing. */
	private boolean isSimple;

	/** Flag indicating this expression is a child of a series simplification result. */
	private boolean isSeriesSimplificationChild;

	/** The series provider that was used to simplify this expression, if any. */
	private KernelSeriesProvider seriesProvider;

	/**
	 * Creates a leaf expression with the specified result type and no children.
	 *
	 * <p>This constructor is typically used for leaf nodes like constants and variables.
	 * Note that {@link #init()} is not called automatically; subclasses should call it
	 * if needed after setting up any additional state.</p>
	 *
	 * @param type the class representing the result type of this expression; must not be null
	 */
	public Expression(Class<T> type) {
		this.type = type;
	}

	/**
	 * Creates an expression with the specified result type and child expressions.
	 *
	 * <p>This constructor automatically calls {@link #init()} to compute tree metrics
	 * and validate the expression structure.</p>
	 *
	 * @param type the class representing the result type of this expression; must not be null
	 * @param children the operand expressions for this operation
	 * @throws IllegalArgumentException if type is null
	 * @throws ExpressionException if the resulting tree is too deep or too large
	 */
	public Expression(Class<T> type, Expression<?>... children) {
		this(type, true, children);
	}

	/**
	 * Creates an expression with the specified result type and child expressions,
	 * with optional deferred initialization.
	 *
	 * <p>This constructor allows subclasses to defer initialization when additional
	 * setup is required before computing tree metrics.</p>
	 *
	 * @param type the class representing the result type of this expression; must not be null
	 * @param init if true, calls {@link #init()} immediately; if false, subclass must call it later
	 * @param children the operand expressions for this operation
	 * @throws IllegalArgumentException if type is null
	 * @throws ExpressionException if init is true and the resulting tree is too deep or too large
	 */
	protected Expression(Class<T> type, boolean init, Expression<?>... children) {
		if (type == null) {
			throw new IllegalArgumentException("Type is required");
		}

		this.type = type;
		this.children = List.of(children);
		if (init) init();
	}

	/**
	 * Initializes computed fields for this expression based on its children.
	 *
	 * <p>This method computes and caches:</p>
	 * <ul>
	 *   <li>Tree depth (maximum child depth + 1)</li>
	 *   <li>Node count (sum of child node counts + 1)</li>
	 *   <li>Hash value for equality checking</li>
	 *   <li>Whether the expression contains long types</li>
	 * </ul>
	 *
	 * <p>This method also validates that the expression does not exceed size limits
	 * defined in {@link ScopeSettings}.</p>
	 *
	 * @throws ExpressionException if the expression exceeds maximum depth or node count limits
	 */
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

	/**
	 * Returns the result type class of this expression.
	 *
	 * @return the class representing the type of value this expression produces
	 */
	public Class<T> getType() { return this.type; }

	/**
	 * Returns the maximum depth of the expression tree rooted at this node.
	 *
	 * <p>A leaf node has depth 0, and the depth of an internal node is
	 * 1 plus the maximum depth of its children.</p>
	 *
	 * @return the tree depth
	 */
	@Override
	public int treeDepth() { return depth; }

	/**
	 * Returns the total number of nodes in the expression subtree rooted at this node.
	 *
	 * @return the node count including this node and all descendants
	 */
	@Override
	public int countNodes() { return nodeCount; }

	/**
	 * Checks if this expression produces an integer result.
	 *
	 * @return {@code true} if the result type is {@link Integer}
	 */
	public boolean isInt() { return getType() == Integer.class; }

	/**
	 * Checks if this expression produces a floating-point result.
	 *
	 * @return {@code true} if the result type is {@link Double}
	 */
	public boolean isFP() { return getType() == Double.class; }

	/**
	 * Checks if this expression represents a null value.
	 *
	 * @return {@code true} if this is a null expression; default implementation returns {@code false}
	 */
	public boolean isNull() { return false; }

	/**
	 * Checks if this expression is masked (e.g., wrapped in a conditional or guard).
	 *
	 * @return {@code true} if this expression is masked; default implementation returns {@code false}
	 */
	public boolean isMasked() { return false; }

	/**
	 * Checks if this expression consists of exactly one index reference.
	 *
	 * @return {@code true} if this is a single index expression; default implementation returns {@code false}
	 */
	public boolean isSingleIndex() { return false; }

	/**
	 * Checks if this expression is a masked single index (a guard around a single index reference).
	 *
	 * @return {@code true} if this is a masked expression whose first child is a single index
	 */
	public boolean isSingleIndexMasked() { return isMasked() && getChildren().get(0).isSingleIndex(); }

	/**
	 * Checks if this expression can produce a concrete value given the specified index assignments.
	 *
	 * <p>This is used to determine if the expression can be evaluated with the given
	 * index values, which is important for sequence generation and optimization.</p>
	 *
	 * @param values the index value assignments to check against
	 * @return {@code true} if this expression can produce a value with these assignments;
	 *         default implementation returns {@code false}
	 */
	public boolean isValue(IndexValues values) { return false; }

	/**
	 * Returns the compile-time boolean value of this expression, if known.
	 *
	 * <p>This method enables constant folding for boolean expressions. Subclasses
	 * representing constant boolean values should override this method.</p>
	 *
	 * @return an {@link Optional} containing the boolean value if this expression
	 *         represents a compile-time constant; empty otherwise
	 */
	public Optional<Boolean> booleanValue() { return Optional.empty(); }

	/**
	 * Returns the compile-time integer value of this expression, if known.
	 *
	 * <p>This method enables constant folding for integer expressions. Subclasses
	 * representing constant integer values should override this method.</p>
	 *
	 * @return an {@link OptionalInt} containing the integer value if this expression
	 *         represents a compile-time constant; empty otherwise
	 */
	public OptionalInt intValue() { return OptionalInt.empty(); }

	/**
	 * Returns the compile-time long value of this expression, if known.
	 *
	 * <p>By default, this returns the integer value promoted to long. Subclasses
	 * representing constant long values should override this method.</p>
	 *
	 * @return an {@link OptionalLong} containing the long value if this expression
	 *         represents a compile-time constant; empty otherwise
	 */
	public OptionalLong longValue() {
		OptionalInt intValue = intValue();
		return intValue.isPresent() ? OptionalLong.of(intValue.getAsInt()) : OptionalLong.empty();
	}

	/**
	 * Returns the compile-time double value of this expression, if known.
	 *
	 * <p>By default, this returns the integer value promoted to double. Subclasses
	 * representing constant floating-point values should override this method.</p>
	 *
	 * @return an {@link OptionalDouble} containing the double value if this expression
	 *         represents a compile-time constant; empty otherwise
	 */
	public OptionalDouble doubleValue() {
		OptionalInt intValue = intValue();
		return intValue.isPresent() ? OptionalDouble.of(intValue.getAsInt()) : OptionalDouble.empty();
	}

	/**
	 * Creates a new expression with all occurrences of a named variable replaced by a constant value.
	 *
	 * <p>This method recursively traverses the expression tree and substitutes any variable
	 * with the given name with the specified numeric value.</p>
	 *
	 * @param name the name of the variable to replace
	 * @param value the numeric value to substitute
	 * @return a new expression with the substitution applied, or this expression if no substitution was needed
	 */
	public Expression<T> withValue(String name, Number value) {
		return generate(getChildren().stream()
				.map(e -> e.withValue(name, value))
				.collect(Collectors.toList()));
	}

	/**
	 * Creates a new expression with all occurrences of a target expression replaced by a replacement.
	 *
	 * <p>This method uses structural equality ({@link #equals(Object)}) to find matching
	 * expressions. If this expression equals the target, it returns the replacement directly.</p>
	 *
	 * @param target the expression to find and replace
	 * @param replacement the expression to substitute for the target
	 * @return a new expression with all substitutions applied
	 */
	public Expression<T> replace(Expression target, Expression replacement) {
		if (this.equals(target)) return replacement;

		return generate(getChildren().stream()
				.map(e -> e.replace(target, replacement))
				.collect(Collectors.toList()));
	}

	/**
	 * Creates a new expression with all occurrences of an index replaced by another expression.
	 *
	 * <p>This is commonly used for index substitution during code generation or when
	 * inlining expressions. If this expression does not contain the specified index,
	 * it returns itself unchanged for efficiency.</p>
	 *
	 * @param index the index to replace
	 * @param e the expression to substitute for the index
	 * @return a new expression with the index substituted, or this expression if the index is not present
	 */
	public Expression<T> withIndex(Index index, Expression<?> e) {
		if (!containsIndex(index)) return this;

		if (this instanceof Index && Objects.equals(((Index) this).getName(), index.getName())) {
			return (Expression) e;
		}

		return generate(getChildren().stream()
				.map(c -> c.withIndex(index, e))
				.collect(Collectors.toList()));
	}

	/**
	 * Creates a new expression with all occurrences of an index replaced by an integer constant.
	 *
	 * <p>This is a convenience method equivalent to {@code withIndex(index, new IntegerConstant(value))}.</p>
	 *
	 * @param index the index to replace
	 * @param value the integer value to substitute
	 * @return a new expression with the index substituted
	 */
	public Expression<T> withIndex(Index index, int value) {
		return withIndex(index, new IntegerConstant(value));
	}

	/**
	 * Returns all {@link Index} instances referenced anywhere in this expression subtree.
	 *
	 * <p>The result is computed once and cached for subsequent calls. For leaf nodes that
	 * are themselves indices, returns a singleton set containing that index. For other
	 * leaf nodes, returns an empty set.</p>
	 *
	 * <p>This method is essential for determining which indices an expression depends on,
	 * which is used for parallelization decisions and code generation.</p>
	 *
	 * @return an unmodifiable set of all indices referenced in this expression
	 */
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

	/**
	 * Attempts to determine the smallest set of distinct integer values (the domain)
	 * for the provided index that would be required to generate all possible results
	 * (the range) for this expression.
	 *
	 * <p>This method may return an empty {@link Optional} even when it is theoretically
	 * possible to determine the domain, if either:</p>
	 * <ul>
	 *   <li>The resulting set would contain more than {@link ScopeSettings#indexOptionLimit} elements</li>
	 *   <li>Some members of the set would exceed {@link Integer#MAX_VALUE}</li>
	 * </ul>
	 *
	 * @param index the index to analyze
	 * @return an {@link Optional} containing the set of required index values,
	 *         or empty if the domain cannot be determined or would be too large
	 */
	public Optional<Set<Integer>> getIndexOptions(Index index) {
		if (this instanceof Index) {
			if (!Objects.equals(this, index)) {
				return Optional.of(Collections.emptySet());
			}

			OptionalLong limit = getLimit();
			if (limit.isEmpty() || limit.getAsLong() > ScopeSettings.indexOptionLimit) {
				return Optional.empty();
			}

			return Optional.of(
					IntStream.range(0, Math.toIntExact(limit.getAsLong()))
						.boxed().collect(Collectors.toSet()));
		}

		Set<Integer> options = new HashSet<>();

		for (Expression<?> e : getChildren()) {
			Optional<Set<Integer>> o = e.getIndexOptions(index);

			if (o.isPresent() && options.size() < ScopeSettings.indexOptionLimit) {
				options.addAll(o.get());
			} else {
				return Optional.empty();
			}
		}

		return options.size() < ScopeSettings.indexOptionLimit ? Optional.of(options) : Optional.empty();
	}

	/**
	 * Returns the kernel structure context associated with this expression.
	 *
	 * <p>The context provides information about the kernel structure, including
	 * maximum kernel size and series providers for optimization. This method
	 * searches children to find a context, returning the first one found.</p>
	 *
	 * @return the kernel structure context, or {@code null} if none is associated
	 */
	public KernelStructureContext getStructureContext() {
		return getChildren().stream()
				.map(Expression::getStructureContext)
				.filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	/**
	 * Checks if this expression or any descendant uses the {@link Long} type.
	 *
	 * <p>This is used to determine if 64-bit integer operations are needed,
	 * which may affect code generation for certain target platforms.</p>
	 *
	 * @return {@code true} if this expression tree contains any long-typed expressions
	 */
	public boolean containsLong() { return containsLong; }

	/**
	 * Checks if this expression contains the specified expression anywhere in its subtree.
	 *
	 * <p>Uses structural equality ({@link #equals(Object)}) for comparison.</p>
	 *
	 * @param e the expression to search for
	 * @return {@code true} if the expression is found in this subtree
	 */
	public boolean contains(Expression e) {
		if (this.equals(e)) return true;
		if (getChildren().isEmpty()) return false;
		return getChildren().stream().anyMatch(c -> c.contains(e));
	}

	/**
	 * Checks if this expression references the specified index anywhere in its subtree.
	 *
	 * <p>Indices are compared by name using {@link Index#getName()}.</p>
	 *
	 * @param idx the index to search for
	 * @return {@code true} if the index is referenced in this subtree
	 */
	public boolean containsIndex(Index idx) {
		if (this instanceof Index && Objects.equals(((Index) this).getName(), idx.getName())) {
			return true;
		} else if (getChildren().isEmpty()) {
			return false;
		}

		return getChildren().stream().anyMatch(e -> e.containsIndex(idx));
	}

	/**
	 * Checks if this expression references the specified variable anywhere in its subtree.
	 *
	 * @param var the variable to search for
	 * @return {@code true} if the variable is referenced in this subtree
	 */
	public boolean containsReference(Variable var) {
		return getChildren().stream().anyMatch(e -> e.containsReference(var));
	}

	/**
	 * Returns the kernel series that describes the repetition pattern of this expression.
	 *
	 * <p>The kernel series is used for optimization to identify expressions that
	 * repeat in a predictable pattern across kernel iterations.</p>
	 *
	 * @return the kernel series; default implementation returns an infinite series
	 */
	public KernelSeries kernelSeries() {
		return KernelSeries.infinite();
	}

	/**
	 * Returns an upper bound on the value this expression can produce.
	 *
	 * <p>For constant expressions, returns the constant value (ceiling for doubles).
	 * Subclasses should override to provide tighter bounds when possible.</p>
	 *
	 * @param context the kernel structure context for bounds analysis
	 * @return an upper bound if determinable; empty otherwise
	 */
	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalInt i = intValue();
		if (i.isPresent()) return OptionalLong.of(i.getAsInt());

		OptionalDouble d = doubleValue();
		if (d.isPresent()) return OptionalLong.of((long) Math.ceil(d.getAsDouble()));

		return OptionalLong.empty();
	}

	/**
	 * Returns a lower bound on the value this expression can produce.
	 *
	 * <p>For constant expressions, returns the constant value (floor for doubles).
	 * Subclasses should override to provide tighter bounds when possible.</p>
	 *
	 * @param context the kernel structure context for bounds analysis
	 * @return a lower bound if determinable; empty otherwise
	 */
	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		OptionalInt i = intValue();
		if (i.isPresent()) return OptionalLong.of(i.getAsInt());

		OptionalDouble d = doubleValue();
		if (d.isPresent()) return OptionalLong.of((long) Math.floor(d.getAsDouble()));

		return OptionalLong.empty();
	}

	/**
	 * Checks if this expression could possibly produce a negative value.
	 *
	 * <p>Uses the lower bound to determine if negative values are possible.
	 * Returns {@code true} if the lower bound is negative or cannot be determined.</p>
	 *
	 * @return {@code true} if this expression might produce negative values
	 */
	public boolean isPossiblyNegative() {
		return lowerBound(null).orElse(-1) < 0;
	}

	/**
	 * Checks if this expression's value is a multiple of another expression's value.
	 *
	 * <p>This method only works for compile-time constant integer expressions.
	 * Returns empty if either expression is not a constant.</p>
	 *
	 * @param e the expression to check divisibility against
	 * @return {@link Optional} containing {@code true} if this value is evenly divisible
	 *         by the other; empty if not determinable at compile time
	 */
	public Optional<Boolean> isMultiple(Expression<?> e) {
		if (intValue().isPresent() && e.intValue().isPresent()) {
			return Optional.of(intValue().getAsInt() % e.intValue().getAsInt() == 0);
		}

		return Optional.empty();
	}

	/**
	 * Evaluates this expression given concrete values for its children.
	 *
	 * <p>This is the core evaluation method that subclasses must override to implement
	 * the actual operation (e.g., addition, multiplication). The default implementation
	 * throws {@link UnsupportedOperationException}.</p>
	 *
	 * @param children the evaluated values of child expressions, in order
	 * @return the result of evaluating this expression
	 * @throws UnsupportedOperationException if this expression type does not support direct evaluation
	 */
	public Number evaluate(Number... children) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Evaluates this expression in batch for multiple sets of child values.
	 *
	 * <p>This method enables parallel evaluation across many input values, which is
	 * more efficient than calling {@link #evaluate(Number...)} repeatedly. The default
	 * implementation uses parallel streams.</p>
	 *
	 * @param children list of arrays, where each array contains one child's values across all iterations
	 * @param len the number of evaluations to perform
	 * @return an array of results, one for each evaluation
	 */
	public Number[] batchEvaluate(List<Number[]> children, int len) {
		return IntStream.range(0, len).parallel()
				.mapToObj(i -> evaluate(children.stream().map(c -> c[i]).toArray(Number[]::new)))
				.toArray(Number[]::new);
	}

	/**
	 * Computes the concrete value of this expression for given index assignments.
	 *
	 * <p>This method recursively evaluates children and combines their results
	 * using {@link #evaluate(Number...)}.</p>
	 *
	 * @param indexValues the index value assignments
	 * @return the computed value
	 */
	@Override
	public Number value(IndexValues indexValues) {
		return evaluate(getChildren().stream().map(e -> e.value(indexValues)).toArray(Number[]::new));
	}

	/**
	 * Generates an index sequence representing this expression's values across its index range.
	 *
	 * <p>This method requires that the expression depends on at most one index.
	 * If the expression has no indices, generates a single-element sequence.
	 * Otherwise, uses the index's limit to determine the sequence length.</p>
	 *
	 * @return the computed index sequence
	 * @throws UnsupportedOperationException if the expression depends on more than one index
	 */
	public IndexSequence sequence() {
		Set<Index> indices = getIndices();
		if (indices.size() > 1) throw new UnsupportedOperationException();

		if (indices.isEmpty()) {
			return sequence(null, 1, Integer.MAX_VALUE);
		}

		return sequence(indices.iterator().next(),
				Math.toIntExact(indices.iterator().next().getLimit().orElse(-1)), Integer.MAX_VALUE);
	}

	/**
	 * Generates an index sequence of the specified length for this expression.
	 *
	 * <p>This method requires that the expression depends on exactly one index.
	 * The sequence will contain values computed for index values 0 through len-1.</p>
	 *
	 * @param len the number of elements in the sequence
	 * @return the computed index sequence
	 * @throws UnsupportedOperationException if the expression does not depend on exactly one index
	 */
	public IndexSequence sequence(int len) {
		Set<Index> indices = getIndices();
		if (indices.size() != 1) throw new UnsupportedOperationException();

		return sequence(indices.iterator().next(), len);
	}

	/**
	 * Generates an index sequence for this expression varying the specified index.
	 *
	 * <p>This method computes the expression's value for each integer assignment
	 * to the given index from 0 to len-1. Results are cached when caching is enabled
	 * in {@link ScopeSettings}.</p>
	 *
	 * <p>For expressions that are simple arithmetic progressions, an optimized
	 * {@link ArithmeticIndexSequence} may be returned instead of computing all values.</p>
	 *
	 * @param index the index to vary
	 * @param len the number of elements in the sequence
	 * @param limit maximum allowed sequence length; returns {@code null} if len exceeds this
	 * @return the computed index sequence, or {@code null} if len exceeds limit
	 * @throws IllegalArgumentException if len is negative or the expression cannot be evaluated
	 */
	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		if (ScopeSettings.enableArithmeticSequence && equals(index)) {
			return new ArithmeticIndexSequence(1, 1, len);
		}

		if (len < 0 || !isValue(new IndexValues().put(index, 0))) {
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

	/**
	 * Returns a simplified version of this expression.
	 *
	 * <p>This method retrieves or creates a kernel structure context and delegates
	 * to {@link #getSimplified(KernelStructureContext)}. If the expression already
	 * has an associated context, it is used; otherwise, a no-op context is created.</p>
	 *
	 * @return a simplified expression equivalent to this one
	 */
	public Expression<?> getSimplified() {
		KernelStructureContext context = getStructureContext();
		if (context != null) {
			return getSimplified(context);
		}

		return getSimplified(new NoOpKernelStructureContext());
	}

	/**
	 * Returns a simplified version of this expression using the given context.
	 *
	 * <p>This is a convenience method that delegates to {@link #getSimplified(KernelStructureContext, int)}
	 * with depth 0.</p>
	 *
	 * @param context the kernel structure context providing optimization hints
	 * @return a simplified expression equivalent to this one
	 */
	public Expression<?> getSimplified(KernelStructureContext context) {
		return getSimplified(context, 0);
	}

	/**
	 * Returns a simplified version of this expression, iteratively applying simplification rules.
	 *
	 * <p>This method repeatedly applies {@link #simplify(KernelStructureContext)} until
	 * the expression stabilizes (no further changes) or is marked as simple. The depth
	 * parameter tracks recursion depth for optimization decisions.</p>
	 *
	 * <p>Simplification includes:</p>
	 * <ul>
	 *   <li>Constant folding (e.g., {@code 2 + 3} becomes {@code 5})</li>
	 *   <li>Identity elimination (e.g., {@code x * 1} becomes {@code x})</li>
	 *   <li>Algebraic simplification (e.g., {@code x - x} becomes {@code 0})</li>
	 *   <li>Series optimization when a series provider is available</li>
	 * </ul>
	 *
	 * @param context the kernel structure context providing optimization hints
	 * @param depth the current recursion depth
	 * @return a simplified expression equivalent to this one
	 */
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

	/**
	 * Returns the simplified expression rendered as target language code.
	 *
	 * <p>This is a convenience method that first simplifies the expression and then
	 * renders it using the specified language operations.</p>
	 *
	 * @param lang the language operations for code generation
	 * @return the simplified expression as code, or {@code null} if simplification fails
	 */
	public String getSimpleExpression(LanguageOperations lang) {
		return Optional.ofNullable(getSimplified())
				.map(e -> e.getExpression(lang)).orElse(null);
	}

	/**
	 * Renders this expression as code in the target language.
	 *
	 * <p>This is the primary code generation method. Each expression subclass must
	 * implement this to produce the appropriate syntax for the target language.
	 * The {@link LanguageOperations} parameter provides language-specific operators,
	 * type names, and syntax rules.</p>
	 *
	 * @param lang the language operations defining the target language syntax
	 * @return the expression rendered as code
	 */
	public abstract String getExpression(LanguageOperations lang);

	/**
	 * Returns this expression rendered as code wrapped in parentheses.
	 *
	 * <p>This is useful for ensuring correct operator precedence when the expression
	 * is used as a subexpression.</p>
	 *
	 * @param lang the language operations for code generation
	 * @return the expression as code wrapped in parentheses
	 */
	public String getWrappedExpression(LanguageOperations lang) {
		return "(" + getExpression(lang) + ")";
	}

	/**
	 * Returns a brief summary of this expression for debugging and logging.
	 *
	 * <p>For shallow expressions (depth less than 10), returns the full expression.
	 * For deeper expressions, returns a summary showing the class name, type, and depth
	 * to avoid generating excessively long strings.</p>
	 *
	 * @return a human-readable summary of this expression
	 */
	public String getExpressionSummary() {
		if (depth < 10) return getExpression(lang);
		return getClass().getSimpleName() + "<" +
					getType().getSimpleName() +
				">[depth=" + depth + "]";
	}

	/**
	 * Searches for subexpressions whose code representation contains the specified text.
	 *
	 * <p>This method performs a depth-first search, returning the deepest (most specific)
	 * subexpressions that contain the text. If no children contain the text but this
	 * expression does, returns this expression.</p>
	 *
	 * @param text the text to search for in the expression code
	 * @return a list of expressions whose code contains the text
	 */
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

	/**
	 * Returns all variables that this expression depends on.
	 *
	 * <p>This method recursively collects all variable references from this expression
	 * and its children. The result is useful for dependency analysis and determining
	 * which variables must be defined before this expression can be evaluated.</p>
	 *
	 * @return a list of all variables referenced in this expression tree
	 */
	public List<Variable<?, ?>> getDependencies() {
		return new ArrayList<>(dependencies(getChildren().toArray(new Expression[0])));
	}

	/**
	 * Returns the compile-time constant value of this expression, if known.
	 *
	 * <p>This method checks for constant values in order: integer, double, boolean.
	 * Returns {@code null} if the expression is not a compile-time constant.</p>
	 *
	 * @return the constant value cast to type T, or {@code null} if not constant
	 */
	public T getValue() {
		OptionalInt i = intValue();
		if (i.isPresent()) return (T) (Integer) i.getAsInt();

		OptionalDouble v = doubleValue();
		if (v.isPresent()) return (T) (Double) v.getAsDouble();

		Optional<Boolean> b = booleanValue();
		if (b.isPresent()) return (T) b.get();

		return null;
	}

	/**
	 * Creates an assignment statement that assigns the given expression to this expression.
	 *
	 * <p>This method is only valid for expressions that can be assignment targets
	 * (l-values), such as variable references or array accesses. The default
	 * implementation throws {@link UnsupportedOperationException}.</p>
	 *
	 * @param exp the expression to assign to this target
	 * @return an assignment statement
	 * @throws UnsupportedOperationException if this expression cannot be an assignment target
	 */
	public ExpressionAssignment<T> assign(Expression exp) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the arithmetic negation of this expression.
	 *
	 * @return an expression representing {@code -this}
	 */
	public Expression minus() { return Minus.of(this); }

	/**
	 * Returns the sum of this expression and an integer constant.
	 *
	 * @param operand the integer value to add
	 * @return an expression representing {@code this + operand}
	 */
	public Expression<? extends Number> add(int operand) { return add(new IntegerConstant(operand)); }

	/**
	 * Returns the sum of this expression and another expression.
	 *
	 * @param operand the expression to add
	 * @return an expression representing {@code this + operand}
	 */
	public Expression<? extends Number> add(Expression<?> operand) { return Sum.of(this, operand); }

	/**
	 * Returns the difference of this expression and another expression.
	 *
	 * @param operand the expression to subtract
	 * @return an expression representing {@code this - operand}
	 */
	public Expression<? extends Number> subtract(Expression<? extends Number> operand) { return Difference.of(this, operand); }

	/**
	 * Returns the difference of this expression and an integer constant.
	 *
	 * @param operand the integer value to subtract
	 * @return an expression representing {@code this - operand}
	 */
	public Expression<? extends Number> subtract(int operand) { return Difference.of(this, new IntegerConstant(operand)); }

	/**
	 * Returns the product of this expression and an integer constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1, returns this expression unchanged.</p>
	 *
	 * @param operand the integer value to multiply by
	 * @return an expression representing {@code this * operand}
	 */
	public Expression<? extends Number> multiply(int operand) {
		return operand == 1 ? (Expression) this : multiply(new IntegerConstant(operand));
	}

	/**
	 * Returns the product of this expression and a long constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1, returns this expression unchanged.</p>
	 *
	 * @param operand the long value to multiply by
	 * @return an expression representing {@code this * operand}
	 */
	public Expression<? extends Number> multiply(long operand) {
		return operand == 1.0 ? (Expression) this : multiply(ExpressionFeatures.getInstance().e(operand));
	}

	/**
	 * Returns the product of this expression and a double constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1.0, returns this expression unchanged.</p>
	 *
	 * @param operand the double value to multiply by
	 * @return an expression representing {@code this * operand}
	 */
	public Expression<? extends Number> multiply(double operand) {
		return operand == 1.0 ? (Expression) this : multiply(Constant.of(operand));
	}

	/**
	 * Returns the product of this expression and another expression.
	 *
	 * @param operand the expression to multiply by
	 * @return an expression representing {@code this * operand}
	 */
	public Expression<? extends Number> multiply(Expression<?> operand) {
		return (Expression) Product.of(this, operand);
	}

	/**
	 * Returns the quotient of this expression divided by an integer constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1, returns this expression unchanged.</p>
	 *
	 * @param operand the integer divisor
	 * @return an expression representing {@code this / operand}
	 */
	public Expression<? extends Number> divide(int operand) {
		return operand == 1 ? (Expression) this : divide(new IntegerConstant(operand));
	}

	/**
	 * Returns the quotient of this expression divided by a long constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1, returns this expression unchanged.</p>
	 *
	 * @param operand the long divisor
	 * @return an expression representing {@code this / operand}
	 */
	public Expression<? extends Number> divide(long operand) {
		return operand == 1 ? (Expression) this : divide(ExpressionFeatures.getInstance().e(operand));
	}

	/**
	 * Returns the quotient of this expression divided by a double constant.
	 *
	 * <p>Optimizes the identity case: if operand is 1.0, returns this expression unchanged.</p>
	 *
	 * @param operand the double divisor
	 * @return an expression representing {@code this / operand}
	 */
	public Expression<? extends Number> divide(double operand) {
		return operand == 1.0 ? (Expression) this : divide(Constant.of(operand));
	}

	/**
	 * Returns the quotient of this expression divided by another expression.
	 *
	 * @param operand the expression divisor
	 * @return an expression representing {@code this / operand}
	 */
	public Expression<? extends Number> divide(Expression<?> operand) {
		return (Expression)Quotient.of(this, operand);
	}

	/**
	 * Returns the reciprocal (multiplicative inverse) of this expression.
	 *
	 * @return an expression representing {@code 1.0 / this}
	 */
	public Expression<? extends Number> reciprocal() { return (Expression) Quotient.of(new DoubleConstant(1.0), this); }

	/**
	 * Returns this expression raised to the power of another expression.
	 *
	 * @param operand the exponent expression
	 * @return an expression representing {@code this ^ operand}
	 */
	public Expression<Double> pow(Expression<Double> operand) { return Exponent.of((Expression) this, operand); }

	/**
	 * Returns the exponential function (e^x) applied to this expression.
	 *
	 * @return an expression representing {@code e^this}
	 */
	public Expression<Double> exp() { return Exp.of(this); }

	/**
	 * Returns the natural logarithm of this expression.
	 *
	 * @return an expression representing {@code ln(this)}
	 */
	public Expression<Double> log() { return Logarithm.of(this); }

	/**
	 * Returns the floor (largest integer not greater than) of this expression.
	 *
	 * <p>For integer expressions, returns this expression unchanged.
	 * For constant double expressions, computes the floor at compile time.</p>
	 *
	 * @return an expression representing {@code floor(this)}
	 */
	public Expression floor() {
		if (getType() == Integer.class) return this;

		OptionalDouble v = doubleValue();
		if (v.isPresent()) return new DoubleConstant(Math.floor(v.getAsDouble()));

		return new Floor((Expression) this);
	}

	/**
	 * Returns the ceiling (smallest integer not less than) of this expression.
	 *
	 * <p>For integer expressions, returns this expression unchanged.
	 * For constant double expressions, computes the ceiling at compile time.</p>
	 *
	 * @return an expression representing {@code ceil(this)}
	 */
	public Expression ceil() {
		if (getType() == Integer.class) return this;

		OptionalDouble v = doubleValue();
		if (v.isPresent()) return new DoubleConstant(Math.ceil(v.getAsDouble()));

		return new Ceiling((Expression) this);
	}

	/**
	 * Returns the floating-point modulo of this expression by another.
	 *
	 * @param operand the divisor expression
	 * @return an expression representing {@code this % operand}
	 */
	public Expression mod(Expression<Double> operand) { return Mod.of(this, operand); }

	/**
	 * Returns the modulo of this expression by another, with configurable floating-point behavior.
	 *
	 * @param operand the divisor expression
	 * @param fp if {@code true}, uses floating-point modulo; otherwise uses integer modulo
	 * @return an expression representing {@code this % operand}
	 */
	public Expression mod(Expression<?> operand, boolean fp) { return Mod.of(this, operand, fp); }

	/**
	 * Returns the integer modulo of this expression by another.
	 *
	 * @param operand the divisor expression
	 * @return an expression representing {@code this % operand} with integer semantics
	 */
	public Expression<Integer> imod(Expression<? extends Number> operand) { return mod(operand, false); }

	/**
	 * Returns the integer modulo of this expression by an integer constant.
	 *
	 * @param operand the integer divisor
	 * @return an expression representing {@code this % operand}
	 */
	public Expression<Integer> imod(int operand) { return imod(new IntegerConstant(operand)); }

	/**
	 * Returns the integer modulo of this expression by a long constant.
	 *
	 * <p>If the operand fits in an integer, uses integer modulo; otherwise uses long modulo.</p>
	 *
	 * @param operand the long divisor
	 * @return an expression representing {@code this % operand}
	 */
	public Expression<Integer> imod(long operand) {
		if (operand > Integer.MAX_VALUE) {
			return imod(new LongConstant(operand));
		} else {
			return imod((int) operand);
		}
	}

	/**
	 * Returns the sine of this expression.
	 *
	 * @return an expression representing {@code sin(this)}
	 */
	public Expression<Double> sin() { return Sine.of((Expression) this); }

	/**
	 * Returns the cosine of this expression.
	 *
	 * @return an expression representing {@code cos(this)}
	 */
	public Expression<Double> cos() { return Cosine.of((Expression) this); }

	/**
	 * Returns the tangent of this expression.
	 *
	 * @return an expression representing {@code tan(this)}
	 */
	public Expression<Double> tan() { return Tangent.of((Expression) this); }

	/**
	 * Returns the hyperbolic tangent of this expression.
	 *
	 * @return an expression representing {@code tanh(this)}
	 */
	public Expression<Double> tanh() { return Tangent.of((Expression) this, true); }

	/**
	 * Returns the logical negation of this boolean expression.
	 *
	 * @return an expression representing {@code !this}
	 * @throws IllegalArgumentException if this expression is not boolean-typed
	 */
	public Expression not() {
		if (getType() != Boolean.class)
			throw new IllegalArgumentException();

		return Negation.of(this);
	}

	/**
	 * Returns an equality comparison of this expression with zero.
	 *
	 * @return an expression representing {@code this == 0.0}
	 */
	public Expression eqZero() { return eq(0.0); }

	/**
	 * Returns an equality comparison of this expression with an integer constant.
	 *
	 * @param operand the integer value to compare against
	 * @return an expression representing {@code this == operand}
	 */
	public Expression eq(int operand) { return eq(new IntegerConstant(operand)); }

	/**
	 * Returns an equality comparison of this expression with a long constant.
	 *
	 * @param operand the long value to compare against
	 * @return an expression representing {@code this == operand}
	 */
	public Expression eq(long operand) { return eq(ExpressionFeatures.getInstance().e(operand)); }

	/**
	 * Returns an equality comparison of this expression with a double constant.
	 *
	 * @param operand the double value to compare against
	 * @return an expression representing {@code this == operand}
	 */
	public Expression eq(double operand) { return eq(new DoubleConstant(operand)); }

	/**
	 * Returns an equality comparison of this expression with another expression.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this == operand}
	 */
	public Expression eq(Expression<?> operand) { return Equals.of(this, operand); }

	/**
	 * Returns an inequality comparison of this expression with another expression.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this != operand}
	 */
	public Expression neq(Expression<?> operand) {
		return Equals.of(this, operand).not();
	}

	/**
	 * Returns the logical conjunction (AND) of this expression with another boolean expression.
	 *
	 * @param operand the boolean expression to AND with
	 * @return an expression representing {@code this && operand}
	 */
	public Expression and(Expression<Boolean> operand) { return Conjunction.of((Expression) this, operand); }

	/**
	 * Returns a conditional expression (ternary operator) using this boolean as the condition.
	 *
	 * @param positive the expression to return if this condition is true
	 * @param negative the expression to return if this condition is false
	 * @return an expression representing {@code this ? positive : negative}
	 * @throws IllegalArgumentException if this expression is not boolean-typed
	 */
	public Expression conditional(Expression<?> positive, Expression<?> negative) {
		if (getType() != Boolean.class) throw new IllegalArgumentException();
		return Conditional.of((Expression<Boolean>) this, positive, negative);
	}

	/**
	 * Returns a greater-than comparison of this expression with another.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this > operand}
	 */
	public Expression<Boolean> greaterThan(Expression<?> operand) { return Greater.of(this, operand); }

	/**
	 * Returns a greater-than-or-equal comparison of this expression with another.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this >= operand}
	 */
	public Expression<Boolean> greaterThanOrEqual(Expression<?> operand) { return Greater.of(this, operand, true); }

	/**
	 * Returns a greater-than-or-equal comparison of this expression with an integer constant.
	 *
	 * @param operand the integer value to compare against
	 * @return an expression representing {@code this >= operand}
	 */
	public Expression<Boolean> greaterThanOrEqual(int operand) { return Greater.of(this, new IntegerConstant(operand), true); }

	/**
	 * Returns a less-than comparison of this expression with another.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this < operand}
	 */
	public Expression<Boolean> lessThan(Expression<?> operand) { return Less.of(this, operand); }

	/**
	 * Returns a less-than comparison of this expression with an integer constant.
	 *
	 * @param operand the integer value to compare against
	 * @return an expression representing {@code this < operand}
	 */
	public Expression<Boolean> lessThan(int operand) { return Less.of(this, new IntegerConstant(operand)); }

	/**
	 * Returns a less-than-or-equal comparison of this expression with another.
	 *
	 * @param operand the expression to compare against
	 * @return an expression representing {@code this <= operand}
	 */
	public Expression<Boolean> lessThanOrEqual(Expression<?> operand) { return Less.of(this, operand, true); }

	/**
	 * Casts this expression to a double-precision floating-point type.
	 *
	 * <p>If this expression is already double-typed, returns it unchanged.</p>
	 *
	 * @return an expression representing {@code (double) this}
	 */
	public Expression<Double> toDouble() {
		if (getType() == Double.class) return (Expression<Double>) this;
		return Cast.of(Double.class, Cast.FP_NAME, this);
	}

	/**
	 * Casts this expression to a 32-bit integer type.
	 *
	 * <p>Only applies a cast if this expression is floating-point typed.</p>
	 *
	 * @return an expression representing {@code (int) this}
	 */
	// TODO  This should also return Expression<? extends Number>
	public Expression<Integer> toInt() {
		return (Expression) toInt(false);
	}

	/**
	 * Casts this expression to a 32-bit integer type with configurable strictness.
	 *
	 * @param require32 if {@code true}, always applies cast unless already Integer;
	 *                  if {@code false}, only casts floating-point types
	 * @return an expression representing {@code (int) this}, or this expression if no cast needed
	 */
	public Expression<? extends Number> toInt(boolean require32) {
		boolean cast = require32 ? getType() != Integer.class : isFP();
		return cast ? Cast.of(Integer.class, Cast.INT_NAME, this) : (Expression) this;
	}

	/**
	 * Casts this expression to a 64-bit long integer type.
	 *
	 * <p>Only applies a cast if this expression is floating-point typed.</p>
	 *
	 * @return an expression representing {@code (long) this}
	 */
	public Expression<? extends Number> toLong() {
		return toLong(false);
	}

	/**
	 * Casts this expression to a 64-bit long integer type with configurable strictness.
	 *
	 * @param require64 if {@code true}, always applies cast unless already Long;
	 *                  if {@code false}, only casts floating-point types
	 * @return an expression representing {@code (long) this}, or this expression if no cast needed
	 */
	public Expression<? extends Number> toLong(boolean require64) {
		boolean cast = require64 ? getType() != Long.class : isFP();
		return cast ? Cast.of(Long.class, Cast.LONG_NAME, this) : (Expression) this;
	}

	/**
	 * Computes the derivative of this expression with respect to the target collection expression.
	 *
	 * <p>This method is used for automatic differentiation. The default implementation
	 * throws {@link UnsupportedOperationException}; subclasses that support differentiation
	 * should override this method.</p>
	 *
	 * @param target the collection expression to differentiate with respect to
	 * @return the derivative expression
	 * @throws UnsupportedOperationException if this expression type does not support differentiation
	 */
	public CollectionExpression<?> delta(CollectionExpression<?> target) {
		throw new UnsupportedOperationException(getClass().getSimpleName());
	}

	/**
	 * Returns the child expressions (operands) of this expression.
	 *
	 * @return an unmodifiable list of child expressions; empty list for leaf nodes
	 */
	@Override
	public List<Expression<?>> getChildren() {
		return children == null ? Collections.emptyList() : children;
	}

	/**
	 * Creates a new expression of the same type with the specified children.
	 *
	 * <p>This method supports expression transformation by creating modified copies.
	 * If the provided children are identical to the current children (by reference),
	 * returns this expression unchanged for efficiency.</p>
	 *
	 * @param children the new child expressions
	 * @return a new expression with the specified children, or this if unchanged
	 */
	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		boolean identical = children.size() == getChildren().size() &&
				IntStream.range(0, children.size())
						.map(i -> children.get(i) == getChildren().get(i) ? 1 : 0)
						.sum() == children.size();
		return identical ? this : recreate(children);
	}

	/**
	 * Creates a new instance of this expression type with the specified children.
	 *
	 * <p>Subclasses must override this method to implement proper expression copying.
	 * The default implementation throws {@link UnsupportedOperationException}.</p>
	 *
	 * @param children the child expressions for the new instance
	 * @return a new expression instance with the specified children
	 * @throws UnsupportedOperationException if this expression type does not support recreation
	 */
	protected Expression<T> recreate(List<Expression<?>> children) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Copies simplification metadata from another expression to this one.
	 *
	 * <p>This method is used during expression transformation to preserve
	 * optimization state like simplification flags and series provider information.</p>
	 *
	 * @param oldExpression the expression to copy metadata from
	 * @return this expression for method chaining
	 */
	protected Expression<T> populate(Expression<?> oldExpression) {
		if (oldExpression.isSimple) this.isSimple = true;
		if (oldExpression.isSeriesSimplificationChild) this.isSeriesSimplificationChild = true;
		if (oldExpression.seriesProvider != null) this.seriesProvider = oldExpression.seriesProvider;
		return this;
	}

	/**
	 * Checks if this expression has been marked as fully simplified.
	 *
	 * <p>An expression is considered simple if it has been explicitly marked as such,
	 * or if it is a leaf node (has no children).</p>
	 *
	 * @return {@code true} if this expression needs no further simplification
	 */
	public boolean isSimple() { return isSimple || getChildren().isEmpty(); }

	/**
	 * Checks if this expression is considered simple within the given kernel context.
	 *
	 * <p>An expression may be simple in one context but require further simplification
	 * in another, particularly when series providers change.</p>
	 *
	 * @param ctx the kernel structure context to check against
	 * @return {@code true} if this expression needs no further simplification in this context
	 */
	public boolean isSimple(KernelStructureContext ctx) {
		if (!isSimple()) return false;
		if (ctx == null) return true;
		if (seriesProvider != null && seriesProvider == ctx.getSeriesProvider()) return true;
		return ctx.getKernelMaximum().isEmpty();
	}

	/**
	 * Flattens this expression, returning its children.
	 *
	 * <p>Subclasses may override this to provide custom flattening behavior,
	 * such as combining nested operations of the same type.</p>
	 *
	 * @return the flattened list of child expressions
	 */
	public List<Expression<?>> flatten() { return getChildren(); }

	/**
	 * Simplifies this expression using the default or embedded context.
	 *
	 * <p>This method retrieves the structure context from the expression tree
	 * or creates a no-op context if none exists.</p>
	 *
	 * @return a simplified expression equivalent to this one
	 */
	public Expression<?> simplify() {
		KernelStructureContext ctx = getStructureContext();
		if (ctx == null) ctx = new NoOpKernelStructureContext();
		return simplify(ctx);
	}

	/**
	 * Simplifies this expression within the given kernel structure context.
	 *
	 * <p>This method delegates to {@link KernelTree#simplify(KernelStructureContext)}
	 * and optionally records the simplification for debugging.</p>
	 *
	 * @param context the kernel structure context providing optimization hints
	 * @return a simplified expression equivalent to this one
	 */
	@Override
	public Expression<?> simplify(KernelStructureContext context) {
		return ScopeSettings.reviewSimplification(this,
				KernelTree.super.simplify(context));
	}

	/**
	 * Performs one level of simplification on this expression and its children.
	 *
	 * <p>This method first simplifies all children, then applies series simplification
	 * if a series provider is available in the context. Series simplification can
	 * convert expressions that depend on kernel indices into precomputed series.</p>
	 *
	 * @param context the kernel structure context providing optimization hints
	 * @param depth the current recursion depth for optimization decisions
	 * @return a simplified expression, possibly with series substitutions
	 */
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

	/**
	 * Checks if this expression should be considered for series simplification at the given depth.
	 *
	 * <p>Series simplification converts index-dependent expressions into precomputed series
	 * when beneficial for performance. The decision depends on expression complexity and depth.</p>
	 *
	 * @param depth the current recursion depth
	 * @return {@code true} if this expression is a candidate for series simplification
	 */
	public boolean isSeriesSimplificationTarget(int depth) {
		return ScopeSettings.isSeriesSimplificationTarget(this, depth);
	}

	/**
	 * Performs structural equality comparison with another expression.
	 *
	 * <p>Two expressions are structurally equal if they have the same type, class,
	 * tree metrics (depth, node count, hash), and recursively equal children.
	 * This method uses cached metrics for efficient early rejection.</p>
	 *
	 * @param e the expression to compare against
	 * @return {@code true} if the expressions are structurally equal
	 */
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

	/**
	 * Checks equality with another object.
	 *
	 * <p>Uses {@link #compare(Expression)} for structural equality. If timing is enabled,
	 * records the duration of the comparison.</p>
	 *
	 * @param obj the object to compare against
	 * @return {@code true} if the object is an expression that is structurally equal to this one
	 */
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Expression)) return false;

		return timing == null ? compare((Expression) obj) :
				timing.recordDuration("expressionEquals", () -> compare((Expression) obj));
	}

	/**
	 * Returns a string signature for this expression.
	 *
	 * <p>The signature is the code representation of the expression, which uniquely
	 * identifies it for caching and deduplication purposes.</p>
	 *
	 * @return the expression rendered as code
	 */
	@Override
	public String signature() {
		return timing == null ? getExpression(lang) :
				timing.recordDuration("expressionSignature", () -> getExpression(lang));
	}

	/**
	 * Returns a hash code for this expression.
	 *
	 * <p>The hash code is computed from the compact hash value, node count, depth,
	 * and number of children, packed into a 32-bit integer. This provides good
	 * distribution while being fast to compute.</p>
	 *
	 * @return the hash code
	 */
	@Override
	public int hashCode() {
		return timing == null ? hash() : timing.recordDuration("expressionHashCode", this::hash);
	}

	/**
	 * Computes the hash code by packing structural metrics into a 32-bit integer.
	 *
	 * @return the computed hash code
	 */
	private int hash() {
		return Bits.put(0, 16, hash) +
				Bits.put(16, 10, nodeCount) +
				Bits.put(26, 4, depth) +
				Bits.put(30, 2, getChildren().size());
	}

	/**
	 * Processes an expression for use in code generation.
	 *
	 * <p>This method performs two optimizations:</p>
	 * <ol>
	 *   <li>If the expression exceeds {@link ScopeSettings#maxNodeCount}, simplifies it</li>
	 *   <li>Checks the expression cache for an equivalent existing expression</li>
	 * </ol>
	 *
	 * @param <T> the expression result type
	 * @param e the expression to process
	 * @return the processed expression, possibly simplified and/or deduplicated
	 * @throws ExpressionException if the expression is too large and simplification doesn't help
	 */
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

	/**
	 * Returns a comparator that orders expressions by depth in descending order.
	 *
	 * <p>Deeper expressions (higher depth values) come before shallower ones.
	 * This ordering is useful for bottom-up simplification where leaf expressions
	 * should be processed before their parents.</p>
	 *
	 * @return a comparator for depth-based ordering
	 */
	public static Comparator<? super Expression> depthOrder() {
		return (a, b) -> {
			int aDepth = a.treeDepth();
			int bDepth = b.treeDepth();
			if (aDepth == bDepth) return 0;
			return aDepth < bDepth ? 1 : -1;
		};
	}

	/**
	 * Converts a numeric value to the appropriate Java type for the given expression type.
	 *
	 * <p>For floating-point types, returns the value as a double. For integer types,
	 * returns an integer if the value fits within integer range, otherwise returns a long.</p>
	 *
	 * @param type the target numeric type class
	 * @param value the numeric value to convert
	 * @return the value converted to the appropriate type
	 */
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

	/**
	 * Sorts expressions by depth in descending order (deepest first).
	 *
	 * <p>This is useful for processing expressions in bottom-up order, ensuring
	 * that children are processed before their parents.</p>
	 *
	 * @param expressions the expressions to sort
	 * @return a new array containing the expressions sorted by depth
	 * @throws UnsupportedOperationException if the sorting operation fails
	 */
	public static Expression[] sort(Expression... expressions) {
		Expression result[] = IntStream.range(0, expressions.length)
				.mapToObj(i -> expressions[i])
				.sorted(depthOrder()).toArray(Expression[]::new);

		if (result.length != expressions.length) {
			throw new UnsupportedOperationException();
		}

		return result;
	}

	/**
	 * Returns the default language operations used for expression rendering.
	 *
	 * <p>This provides access to the shared language operations instance used
	 * when no specific language context is provided.</p>
	 *
	 * @return the default language operations
	 */
	public static LanguageOperations defaultLanguage() {
		return lang;
	}

	/**
	 * Caches an index sequence for an expression string if caching is enabled.
	 *
	 * @param exp the expression string key
	 * @param seq the index sequence to cache
	 */
	private static void cacheSeq(String exp, IndexSequence seq) {
		if (kernelSeqCache != null && exp != null) {
			kernelSeqCache.put(exp, seq);
		}
	}

	/**
	 * Collects all variable dependencies from a set of expressions.
	 *
	 * @param expressions the expressions to analyze
	 * @return a set of all variables referenced by any of the expressions
	 */
	private static Set<Variable<?, ?>> dependencies(Expression expressions[]) {
		Set<Variable<?, ?>> dependencies = new HashSet<>();
		for (Expression e : expressions) dependencies.addAll(e.getDependencies());
		return dependencies;
	}
}
