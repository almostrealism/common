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

package io.almostrealism.kernel;

import io.almostrealism.sequence.ArithmeticIndexSequence;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.sequence.KernelSeriesMatcher;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.scope.ScopeSettings;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Converts arbitrary kernel expressions to series form for more efficient code generation.
 *
 * <p>A {@code KernelSeriesProvider} inspects an {@link Expression} that depends on the
 * kernel index and, where possible, returns a simpler expression that produces the same
 * sequence of values at every kernel position. Two mechanisms are involved:</p>
 * <ul>
 *   <li><b>derivation</b> — index arithmetic that follows an arithmetic progression is
 *       recognised from its structure alone via
 *       {@link Expression#arithmeticSequence(Index, long)}, in time proportional to the
 *       expression rather than to the kernel size;</li>
 *   <li><b>recognition</b> — otherwise the sequence is matched against the closed forms a
 *       {@link KernelSeriesMatcher} knows (a constant, a mask, an arithmetic progression).
 *       This is generic and needs nothing from the provider. The values are enumerated
 *       only as far as necessary: a sequence matching no form is abandoned at the first
 *       position that rules every form out;</li>
 *   <li><b>storage</b> — a provider that can hold the sequence as kernel-resident data
 *       (see {@link #isSeriesStorable(int, long)} and
 *       {@link #referenceSeries(Expression, IndexSequence, boolean)}) receives the fully
 *       enumerated sequence when recognition fails, and returns a lookup into the stored
 *       copy. The default provider stores nothing.</li>
 * </ul>
 *
 * <p>Implementations are obtained from a {@link KernelStructureContext} and are invoked
 * automatically during expression simplification via
 * {@link KernelStructureContext#simplify(Expression)}.</p>
 *
 * @see KernelStructureContext
 * @see KernelSeriesMatcher
 * @see ScopeSettings
 */
public interface KernelSeriesProvider extends OperationInfo, Destroyable {
	/**
	 * Returns the maximum number of element-wise operations the provider will execute
	 * when computing a sequence for matching.
	 *
	 * @return the sequence computation limit; defaults to {@link Integer#MAX_VALUE}
	 */
	default long getSequenceComputationLimit() {
		return Integer.MAX_VALUE;
	}

	/**
	 * Returns the smallest {@link Expression#countNodes() node count} an expression
	 * must have to be worth converting. Smaller expressions are returned unchanged
	 * without enumerating anything.
	 *
	 * @return the minimum node count; defaults to 0 (every expression is considered)
	 */
	default int getMinimumNodeCount() {
		return 0;
	}

	/**
	 * Returns {@code true} if this provider could store the sequence of an expression
	 * with the given node count and length, should recognition find no closed form.
	 *
	 * <p>When this is {@code true} the sequence is enumerated completely before
	 * recognition, since the values are needed either way. When it is {@code false}
	 * recognition stops as soon as every form is refuted.</p>
	 *
	 * @param nodes the expression's node count
	 * @param len   the sequence length
	 * @return whether {@link #referenceSeries} may produce a result; defaults to {@code false}
	 */
	default boolean isSeriesStorable(int nodes, long len) {
		return false;
	}

	/**
	 * Stores the fully enumerated sequence of an expression that matched no closed
	 * form and returns an expression reading the stored copy at the given index.
	 *
	 * <p>Called only when {@link #isSeriesStorable(int, long)} returned {@code true}
	 * for the expression. A provider without storage returns {@code null}, which is
	 * the default.</p>
	 *
	 * @param index the index expression over which the sequence was enumerated
	 * @param seq   the complete sequence of values
	 * @param isInt {@code true} if the expression produces integer values
	 * @return an expression reading the stored sequence, or {@code null} if it was not stored
	 */
	default Expression referenceSeries(Expression index, IndexSequence seq, boolean isInt) {
		return null;
	}

	/**
	 * Attempts to convert the given expression to a simpler series form by detecting
	 * the relevant index (kernel index or child index) and delegating to
	 * {@link #getSeries(Expression, Index)}.
	 *
	 * @param <T> the expression type
	 * @param exp the expression to convert
	 * @return a simplified series expression, or {@code exp} if no conversion is possible
	 */
	default <T> Expression<T> getSeries(Expression<T> exp) {
		if (exp instanceof Index || exp.doubleValue().isPresent()) return exp;

		Set<Index> indices = exp.getIndices();
		if (indices.isEmpty()) return getSeries(exp, new KernelIndex());

		Optional<Index> c = indices.stream()
				.filter(i -> i instanceof KernelIndexChild)
				.findFirst();
		Optional<Index> k = indices.stream()
				.filter(i -> i instanceof KernelIndex)
				.findFirst();
		return getSeries(exp, c.orElse(k.orElse(indices.iterator().next())));
	}

	/**
	 * Attempts to convert the given expression to a series form using the specified index.
	 *
	 * <p>The progression of the expression over all positions of the index (up to the
	 * index limit or the provider's maximum length) is first derived structurally via
	 * {@link Expression#arithmeticSequence(Index, long)} and confirmed on a sample of
	 * positions. Failing that, the values are recognised by a {@link KernelSeriesMatcher};
	 * if that fails too and the provider can store the sequence, {@link #referenceSeries}
	 * is offered the enumerated values. For boolean expressions the result is wrapped in
	 * an equality test. Returns {@code exp} unchanged if conversion is not possible.</p>
	 *
	 * @param exp   the expression to convert
	 * @param index the index variable to use for sequence evaluation
	 * @return a simplified series expression, or {@code exp} if no conversion is possible
	 */
	default Expression getSeries(Expression exp, Index index) {
		if (exp instanceof Index || exp.doubleValue().isPresent()) return exp;
		if (!(index instanceof Expression)) return exp;

		OptionalLong len = index.getLimit();

		if (!len.isPresent() && getMaximumLength().isPresent()) {
			len = index.upperBound(
							new NoOpKernelStructureContext(getMaximumLength().getAsInt()))
					.stream().map(i -> i + 1).findFirst();
		}

		if (!len.isPresent() || len.getAsLong() > Integer.MAX_VALUE) return exp;

		long start = System.nanoTime();

		Expression result = null;

		try {
			int nodes = exp.countNodes();

			if (nodes >= getMinimumNodeCount() && exp.isValue(new IndexValues().put(index, 0))) {
				int l = Math.toIntExact(len.getAsLong());
				boolean isInt = !exp.isFP();

				ArithmeticIndexSequence derived = exp.arithmeticSequence(index, l);

				if (derived != null && !derived.agreesWith(exp, index)) {
					derived.warn("Derived series " + derived + " disagrees with " +
							exp.getExpression(Expression.defaultLanguage()));
					derived = null;
				}

				if (derived != null) {
					result = derived.getExpression((Expression) index, isInt);
				} else if (isSeriesStorable(nodes, l)) {
					IndexSequence seq = exp.sequence(index, l, getSequenceComputationLimit());

					if (seq != null) {
						result = seq.getExpression((Expression) index, isInt);
						if (result == null) result = referenceSeries((Expression) index, seq, isInt);
					}
				} else {
					KernelSeriesMatcher matcher = exp.matchSeries(index, l, getSequenceComputationLimit());
					if (matcher != null) result = matcher.getExpression((Expression) index, isInt);
				}

				if (result != null && exp.getType() == Boolean.class) {
					OptionalDouble d = result.doubleValue();

					if (d.isPresent()) {
						result = new BooleanConstant(d.getAsDouble() == 1.0);
					} else {
						result = result.eq(1.0);
					}
				}
			}
		} finally {
			if (ScopeSettings.timing != null) {
				boolean isPos = result != null;
				String stage = "kernelSeries [" + exp.treeDepth() +
						"/" + exp.countNodes() + ", " + isPos + "]";
				ScopeSettings.timing.recordDuration(getMetadata(),
						stage, System.nanoTime() - start);
			}
		}

		return result == null ? exp : result;
	}

	/**
	 * Returns the maximum sequence length this provider will evaluate, or empty if unbounded.
	 *
	 * @return the maximum sequence length
	 */
	OptionalInt getMaximumLength();

	/** {@inheritDoc} */
	@Override
	default String describe() {
		return getMetadata().getShortDescription();
	}
}
