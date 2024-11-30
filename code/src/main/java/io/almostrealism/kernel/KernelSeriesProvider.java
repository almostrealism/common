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

package io.almostrealism.kernel;

import io.almostrealism.code.CachedValue;
import io.almostrealism.code.OperationInfo;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.io.TimingMetric;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public interface KernelSeriesProvider extends OperationInfo, Destroyable {
	LanguageOperations lang = new LanguageOperationsStub();

	default long getSequenceComputationLimit() {
		return Integer.MAX_VALUE;
	}

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
				ScopeSettings.timing.recordDuration(getMetadata(),
						"kernelSeries [" + exp.treeDepth() +
								"/" + exp.countNodes() + ", " + isPos + "]",
						System.nanoTime() - start);
			}
		}

		return result == null ? exp : result;
	}

	Expression getSeries(Expression index, Supplier<String> exp, Supplier<IndexSequence> seq, boolean isInt, IntSupplier nodes);

	OptionalInt getMaximumLength();

	@Override
	default String describe() {
		return getMetadata().getShortDescription();
	}
}
