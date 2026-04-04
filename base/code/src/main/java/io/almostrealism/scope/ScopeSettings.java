/*
 * Copyright 2026 Michael Murray
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

package io.almostrealism.scope;

import io.almostrealism.compute.ParallelismTargetOptimization;
import io.almostrealism.expression.Expression;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.profile.ScopeTimingListener;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Global configuration flags and thresholds that control the behaviour of
 * {@link Scope} construction, simplification, expression caching, and kernel
 * sequence evaluation during code generation.
 *
 * <p>Most settings are controlled via system properties (e.g. {@code AR_SCOPE_SIMPLIFICATION},
 * {@code AR_SCOPE_CACHING}) and can be overridden at runtime for testing or tuning purposes.</p>
 *
 * @see Scope
 * @see ExpressionCache
 * @see SimplificationSettings
 * @see CachingSettings
 */
public class ScopeSettings {
	/** When {@code true}, common sub-expression replacement is enabled during simplification. */
	public static final boolean enableReplacements = true;

	/**
	 * When {@code true}, instruction-set sub-expressions are eligible for reuse across
	 * compilation units. Controlled by {@code AR_INSTRUCTION_SET_REUSE}.
	 */
	public static boolean enableInstructionSetReuse =
			SystemUtils.isEnabled("AR_INSTRUCTION_SET_REUSE").orElse(true);

	/** When {@code true}, masking is applied to instance-level reference expressions. */
	public static boolean enableInstanceReferenceMasking = false;

	/** When {@code true}, kernel sequence results are cached to avoid recomputation. */
	public static boolean enableKernelSeqCache = false;

	/** When {@code true}, batch evaluation of sequences is enabled. */
	public static boolean enableBatchEvaluation = false;

	/** When {@code true}, arithmetic sequences are used during expression evaluation. */
	public static boolean enableArithmeticSequence = true;

	/** When {@code true}, sequence computation results are validated against original expressions. */
	public static boolean enableSequenceValidation = false;

	/** Maximum number of elements per cache item for kernel sequence caching. */
	public static int maxCacheItemSize = 16;

	/** Maximum number of items stored in the kernel sequence cache. */
	public static int maxCacheItems = 128;

	/**
	 * The maximum depth of any {@link Expression} as
	 * measured by {@link Expression#treeDepth()}.
	 */
	public static int maxDepth = 512;

	/**
	 * The maximum total number of nodes allowed in any
	 * {@link Expression} as measured by
	 * {@link Expression#countNodes()}.
	 */
	public static int maxNodeCount = 1 << 23;

	/**
	 * The maximum number of possible options to consider
	 * for the value of any {@link Index} when evaluating
	 * complexity in {@link Expression#getIndexOptions(Index)}.
	 */
	public static int indexOptionLimit = 1 << 15;

	/**
	 * Maximum number of statements allow in a {@link Scope}.
	 * {@link io.almostrealism.code.Computation} which exceed
	 * this limit may produce an error rather than be compiled.
	 *
	 * @see Scope#getStatements()
	 */
	public static int maxStatements = 1 << 16;

	/**
	 * The preferred limit for statements in a {@link Scope}.
	 * {@link io.almostrealism.code.Computation}s can exceed
	 * this limit, but should make a best effort to avoid it.
	 *
	 * @see Scope#getStatements()
	 */
	public static int preferredStatements = 1 << 8;

	/**
	 * Maximum number of nested conditions for any {@link Expression}
	 * in a {@link Scope}.
	 */
	public static int maxConditionSize = 32;

	/**
	 * When {@code true}, expression tree warnings (oversized trees, excessive nodes)
	 * are emitted to the log. Controlled by {@code AR_EXPRESSION_WARNINGS}.
	 */
	public static boolean enableExpressionWarnings =
			SystemUtils.isEnabled("AR_EXPRESSION_WARNINGS").orElse(true);

	/**
	 * When {@code true}, expressions are reviewed after simplification to verify they
	 * produce correct results. Controlled by {@code AR_EXPRESSION_REVIEW}.
	 */
	public static boolean enableExpressionReview =
			SystemUtils.isEnabled("AR_EXPRESSION_REVIEW").orElse(false);

	/** Running count of expressions that were simplified during this JVM session. */
	public static long simplificationCount = 0;

	/** Running count of expression children that have not yet been simplified. */
	public static long unsimplifiedChildren = 0;

	/** Running count of expressions that were added to an {@link ExpressionCache} during this session. */
	public static long cacheCount = 0;

	/** Maximum number of elements in a kernel series before it is truncated. */
	public static int maxKernelSeriesCount = ParallelismTargetOptimization.maxCount << 2;

	/** Upper bound on the number of operations evaluated during sequence computation. */
	public static int sequenceComputationLimit = maxKernelSeriesCount;

	/** Optional listener that records scope simplification timing; {@code null} when disabled. */
	public static ScopeTimingListener timing;

	/** The active simplification strategy, initialised from {@code AR_SCOPE_SIMPLIFICATION}. */
	private static SimplificationSettings simplification;

	/** The active expression caching strategy, initialised from {@code AR_SCOPE_CACHING}. */
	private static CachingSettings caching;

	static {
		String defaultSimplify = "1.0";
		String simplify = SystemUtils.getProperty("AR_SCOPE_SIMPLIFICATION", "enabled");
		simplify = "enabled".equalsIgnoreCase(simplify) ? defaultSimplify : simplify;

		if (simplify.equalsIgnoreCase("disabled")) {
			simplification = SimplificationSettings.none();
		} else if (simplify.equalsIgnoreCase("tiered")) {
			simplification = new TieredSimplificationSettings();
		} else {
			if (!Objects.equals(defaultSimplify, simplify))
				System.out.println("SpectrumSimplification[" + simplify + "]");

			simplification = new SpectrumSimplification(Double.parseDouble(simplify));
		}

		String cd = "0.2:0.2";
		String cache = SystemUtils.getProperty("AR_SCOPE_CACHING", cd);

		if (cache.equalsIgnoreCase("explicit")) {
			caching = new ExplicitDepthCaching();
		} else {
			if (!Objects.equals(cd, cache))
				System.out.println("SpectrumCaching[" + cache + "]");

			String c[] = cache.split(":");
			caching = new SpectrumCaching(Double.parseDouble(c[0]), Double.parseDouble(c[1]));
		}
	}

	/**
	 * Reviews the supplied child expressions and increments the {@link #unsimplifiedChildren}
	 * counter if any child could still be simplified. A diagnostic message is logged every
	 * 100 occurrences when {@link #enableExpressionReview} is enabled.
	 *
	 * @param children the list of child expressions to inspect
	 */
	public static void reviewChildren(List<Expression<?>> children) {
		if (!enableExpressionReview) return;

		KernelStructureContext ctx = children.stream()
				.map(Expression::getStructureContext)
				.filter(Objects::nonNull)
				.findFirst().orElse(new NoOpKernelStructureContext());

		boolean s = IntStream.range(0, children.size())
				.filter(i -> !Objects.equals(children.get(i), children.get(i).simplify(ctx)))
				.findFirst().orElse(-1) >= 0;
		if (!s) return;

		unsimplifiedChildren++;

		if (unsimplifiedChildren % 100 == 0) {
			Scope.console.features(ScopeSettings.class)
					.log("Unsimplified Children = " + unsimplifiedChildren);
		}
	}

	/**
	 * Reviews the result of a simplification step by comparing the output sequence of
	 * {@code simplified} against the original {@code expression} for the first index
	 * found in the simplified result. Returns {@code simplified} unchanged.
	 *
	 * @param <T>        the expression type
	 * @param expression the original unsimplified expression
	 * @param simplified the expression after simplification
	 * @return {@code simplified} (validation is a side effect only)
	 */
	public static <T> Expression<T> reviewSimplification(Expression<?> expression, Expression<T> simplified) {
		Index target = simplified.getIndices().stream().findFirst().orElse(null);
		return reviewSimplification(target, expression, simplified);
	}

	/**
	 * Reviews the result of a simplification step using the specified index target, comparing
	 * the output sequence at index value 0 when the index has a finite limit.
	 *
	 * @param <T>        the expression type
	 * @param target     the index to use when evaluating the sequences; may be {@code null}
	 * @param expression the original unsimplified expression
	 * @param simplified the expression after simplification
	 * @return {@code simplified} (validation is a side effect only)
	 */
	public static <T> Expression<T> reviewSimplification(Index target, Expression<?> expression, Expression<T> simplified) {
		if (!enableSequenceValidation || target == null) return simplified;

		IndexValues v = new IndexValues();
		v.put(target, 0);

		if (target.getLimit().isPresent() &&
				target.getLimit().orElse(0) < Integer.MAX_VALUE) {
			return reviewSimplification(v, expression, simplified);
		}

		return simplified;
	}

	/**
	 * Reviews the result of a simplification step at the provided index values by comparing
	 * the output sequences of the original and simplified expressions. Throws if they diverge.
	 *
	 * @param <T>        the expression type
	 * @param values     the index values at which to evaluate both expressions; may be {@code null}
	 * @param expression the original unsimplified expression
	 * @param simplified the expression after simplification
	 * @return {@code simplified} (validation is a side effect only)
	 * @throws UnsupportedOperationException if the sequences are not congruent
	 */
	public static <T> Expression<T> reviewSimplification(IndexValues values, Expression<?> expression, Expression<T> simplified) {
		if (!enableSequenceValidation || values == null) return simplified;

		if (simplified.isValue(values)) {
			IndexSequence orig = expression.sequence();
			IndexSequence seq = simplified.sequence();

			if (!orig.congruent(seq)) {
				throw new UnsupportedOperationException();
			}
		}

		return simplified;
	}

	/**
	 * Returns {@code true} if the given expression should be simplified using series-based
	 * techniques at the specified tree depth. Increments {@link #simplificationCount} when
	 * the active {@link SimplificationSettings} approves the expression.
	 *
	 * @param expression the expression to evaluate
	 * @param depth      the current recursion depth within the expression tree
	 * @return {@code true} if series simplification should be applied
	 */
	public static boolean isSeriesSimplificationTarget(Expression<?> expression, int depth) {
		boolean s = simplification.isSeriesSimplificationTarget(expression, depth);
		if (s) {
			simplificationCount++;
		}

		return s;
	}

	/**
	 * Returns {@code true} if deep (recursive) simplification is currently enabled.
	 *
	 * @return {@code false} always (deep simplification is not currently activated)
	 */
	public static boolean isDeepSimplification() {
		return false;
	}

	/**
	 * Returns the maximum number of entries that each per-depth sub-cache inside
	 * an {@link ExpressionCache} can hold.
	 *
	 * @return the expression cache capacity
	 */
	public static int getExpressionCacheSize() { return 300; }

	/**
	 * Returns the minimum hit count for an expression to be included in the list of
	 * frequently occurring expressions returned by {@link ExpressionCache#getFrequentExpressions()}.
	 *
	 * @return the frequency threshold
	 */
	public static int getExpressionCacheFrequencyThreshold() { return 2; }

	/**
	 * Returns the minimum total compute cost for an expression to be unconditionally
	 * eligible for caching, bypassing structural filters (depth sets, hash checks).
	 *
	 * <p>Expressions whose {@link io.almostrealism.expression.Expression#totalComputeCost()}
	 * meets or exceeds this threshold are always considered cache targets, ensuring that
	 * expensive operations like transcendentals are never filtered out by depth-based caching.</p>
	 *
	 * @return the compute cost threshold for unconditional caching eligibility
	 */
	public static int getComputeCostCacheThreshold() { return 15; }

	/**
	 * Returns the maximum base expression compute cost for which exponent
	 * strength reduction (e.g. {@code pow(x,2) → x*x}) is applied.
	 *
	 * <p>When the base expression's {@link Expression#totalComputeCost()} is
	 * at or above this threshold, the base is too expensive to duplicate in a
	 * product and {@code pow()} is retained instead. This prevents expressions
	 * like {@code pow(sin(x), 2)} from expanding into {@code sin(x)*sin(x)},
	 * which would double the transcendental evaluations.</p>
	 *
	 * @return the compute cost threshold for exponent strength reduction
	 */
	public static int getStrengthReductionCostThreshold() { return 100; }

	/**
	 * Determines whether the given expression should be cached (extracted into a
	 * named declaration for reuse).
	 *
	 * <p>An expression is eligible for caching if either:</p>
	 * <ul>
	 *   <li>Its {@link Expression#totalComputeCost()} meets or exceeds the
	 *       {@linkplain #getComputeCostCacheThreshold() compute cost threshold},
	 *       ensuring expensive operations (transcendentals, etc.) are always cached
	 *       regardless of the active caching strategy; or</li>
	 *   <li>The active {@link CachingSettings} implementation accepts it based on
	 *       structural criteria (tree depth, hash, etc.).</li>
	 * </ul>
	 *
	 * @param expression the expression to evaluate for caching eligibility
	 * @return true if the expression should be cached
	 */
	public static boolean isExpressionCacheTarget(Expression<?> expression) {
		if (expression.totalComputeCost() >= getComputeCostCacheThreshold()) {
			cacheCount++;
			return true;
		}

		boolean c = caching.isExpressionCacheTarget(expression);
		if (c) {
			cacheCount++;
		}
		return c;
	}

	/**
	 * Returns the maximum number of common sub-expression replacements per scope
	 * during the CSE pass in {@link Scope#simplify}. Each replacement extracts a
	 * frequently-occurring sub-expression into a named declaration variable.
	 *
	 * <p>This limit was previously raised to 48 to accommodate genome-only
	 * sub-expression extraction for LICM, but that caused an 11x code blowup
	 * in the AudioScene loop body (from 251 to 2,783 lines). The CSE pass does
	 * not prioritize loop-invariant sub-expressions, so raising the limit causes
	 * it to extract loop-variant sub-expressions that inflate the code without
	 * enabling more hoisting. Reverted to 12 per regression analysis.</p>
	 *
	 * @return the maximum number of CSE replacements per scope
	 * @see Repeated
	 */
	public static int getMaximumReplacements() {
		return 12;
	}

	/**
	 * Logs the current simplification and cache counts to the scope console.
	 */
	public static void printStats() {
		Scope.console.features(ScopeSettings.class)
				.log("Simplification Count = " + simplificationCount +
						" | Cache Count = " + cacheCount);
	}

	/**
	 * Returns a short description of the active caching and simplification strategies,
	 * useful for logging and profiling output.
	 *
	 * @return a combined short description of the form {@code <caching>_<simplification>}
	 */
	public static String shortDesc() {
		return caching.shortDesc() + "_" + simplification.shortDesc();
	}
}
