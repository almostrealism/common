/*
 * Copyright 2021 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Factor;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.TemporalChromosomeExpansion;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

@Deprecated
public abstract class TemporalCellularChromosomeExpansion<T, I, O> extends TemporalChromosomeExpansion<T, I, O> {
	public TemporalCellularChromosomeExpansion(Chromosome<I> source) {
		super(source);
	}

	@Override
	protected Factor<O> factor(int pos, int factor, Gene<O> gene) {
		return cell(pos, factor, gene).toFactor(value(), assignment(), combine());
	}

	protected abstract Cell<O> cell(int pos, int factor, Gene<O> gene);

	protected abstract Supplier<O> value();

	protected abstract Function<Producer<O>, Receptor<O>> assignment();

	protected abstract BiFunction<Producer<O>, Producer<O>, Producer<O>> combine();
}
