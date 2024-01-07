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

import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Index;
import io.almostrealism.expression.IndexValues;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.expression.KernelIndexChild;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.TimingMetric;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface KernelSeriesProvider {
	TimingMetric timing = Scope.console.timing("kernelSeries");

	default Expression getSeries(Expression exp) {
		if (exp instanceof Index || exp.doubleValue().isPresent()) return exp;

		Set<Index> indices = exp.getIndices();
		Optional<Index> c = indices.stream()
				.filter(i -> i instanceof KernelIndexChild)
				.findFirst();
		return getSeries(exp, c.orElse(new KernelIndex()));
	}

	default Expression getSeries(Expression exp, Index index) {
		if (exp instanceof Index || exp.doubleValue().isPresent()) return exp;
		if (!(index instanceof Expression)) return exp;

		OptionalInt len = index.getLimit();

		if (!len.isPresent()) {
			len = ((Expression) index)
					.upperBound(new NoOpKernelStructureContext(getMaximumLength().getAsInt()))
					.stream().map(i -> i + 1).findFirst();
		}

		if (!len.isPresent()) return exp;

		long start = System.nanoTime();

		Expression result = null;

		try {
			if (exp.isKernelValue(new IndexValues().put(index, 0))) {
				if (exp.getType() == Boolean.class) {
					result = getSeries((Expression) index, exp.sequence(index, len.getAsInt()), true, exp::countNodes);

					if (result != null) {
						OptionalDouble d = result.doubleValue();
						if (d.isPresent()) {
							return d.getAsDouble() == 1.0 ? new BooleanConstant(true) : new BooleanConstant(false);
						} else {
							result = result.eq(1.0);
						}
					}
				} else {
					result = getSeries((Expression) index, exp.sequence(index, len.getAsInt()),
								exp.getType() == Integer.class, exp::countNodes);
				}
			}
		} finally {
			timing.addEntry(exp.countNodes() + "-" + (result != null), System.nanoTime() - start);
		}

		return result == null ? exp : result;
	}

	Expression getSeries(Expression index, IndexSequence seq, boolean isInt, IntSupplier nodes);

	OptionalInt getMaximumLength();
}
