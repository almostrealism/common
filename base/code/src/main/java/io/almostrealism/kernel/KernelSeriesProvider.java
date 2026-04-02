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

import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.code.CachedValue;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.scope.ScopeSettings;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Converts arbitrary kernel expressions to series form for more efficient code generation.
 *
 * <p>A {@code KernelSeriesProvider} inspects an {@link Expression} that depends on the
 * kernel index, evaluates its sequence of values at every kernel position, and (where
 * possible) returns a simpler expression that produces the same sequence — for example,
 * a constant, an arithmetic progression, or a lookup table.</p>
 *
 * <p>Implementations are obtained from a {@link KernelStructureContext} and are invoked
 * automatically during expression simplification via
 * {@link KernelStructureContext#simplify(Expression)}.</p>
 *
 * @see KernelStructureContext
 * @see ScopeSettings
 */
public interface KernelSeriesProvider extends OperationInfo, Destroyable {
	/** A stub language backend used to render expression strings for matching. */
	LanguageOperations lang = new LanguageOperationsStub();

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
	 * <p>The provider evaluates the expression's sequence over all positions of the index
	 * (up to the index limit or the provider's maximum length) and delegates to the
	 * underlying matching implementation. For boolean expressions the result is wrapped
	 * in an equality test. Returns {@code exp} unchanged if conversion is not possible.</p>
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
			if (exp.isValue(new IndexValues().put(index, 0))) {
				CachedValue<IndexSequence> seq = new CachedValue<>(args ->
						exp.sequence(index, ((Number) args[0]).longValue(), getSequenceComputationLimit()));
				seq.setAllowNull(true);

				int l = Math.toIntExact(len.getAsLong());

				if (exp.getType() == Boolean.class) {
					result = getSeries((Expression) index,
									() -> exp.getExpression(lang),
									() -> seq.evaluate(Integer.valueOf(l)),
								true, exp::countNodes);

					if (result != null) {
						OptionalDouble d = result.doubleValue();
						if (d.isPresent()) {
							return d.getAsDouble() == 1.0 ? new BooleanConstant(true) : new BooleanConstant(false);
						} else {
							result = result.eq(1.0);
						}
					}
				} else {
					result = getSeries((Expression) index,
								() -> exp.getExpression(lang),
								() -> seq.evaluate(Integer.valueOf(l)),
								!exp.isFP(), exp::countNodes);
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
	 * Core matching method that takes a pre-computed sequence and attempts to return
	 * a simpler expression producing the same values.
	 *
	 * @param index  the index expression over which the sequence was computed
	 * @param exp    supplier for the expression's rendered string (used for matching)
	 * @param seq    supplier for the pre-computed sequence of values
	 * @param isInt  {@code true} if the expression produces integer values
	 * @param nodes  supplier for the expression's node count (used for threshold checks)
	 * @return a simpler expression, or {@code null} if no match was found
	 */
	Expression getSeries(Expression index, Supplier<String> exp, Supplier<IndexSequence> seq, boolean isInt, IntSupplier nodes);

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
