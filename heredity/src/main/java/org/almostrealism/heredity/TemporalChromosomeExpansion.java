/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.heredity;

import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;
import org.almostrealism.time.TemporalList;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Deprecated
public abstract class TemporalChromosomeExpansion<T, I, O> implements Chromosome<O>, Temporal, Setup {
	private Chromosome<I> source;
	private Map<Integer, Function<Gene<I>, Producer<O>>> transforms;

	private ArrayListChromosome<O> destination;

	public TemporalChromosomeExpansion(Chromosome<I> source) {
		this.source = source;
		this.transforms = new HashMap<>();
	}

	public Chromosome<I> getSource() { return source; }

	public abstract void setTimeline(T timeline);

	public void setTransform(int factor, Function<Gene<I>, Producer<O>> transform) {
		this.transforms.put(factor, transform);
	}

	public Function<Gene<I>, Producer<O>> identity(int index, Producer<I> identity) {
		return g -> (Producer<O>) g.valueAt(index).getResultant(identity);
	}

	public Supplier<Runnable> expand() {
		OperationList prepare = new OperationList("TemporalChromosome Preparation");
		prepare.add(setup());
		prepare.add(() -> () -> {
			ArrayListChromosome<O> destination = new ArrayListChromosome<>();

			for (int i = 0; i < source.length(); i++) {
				destination.add(new ArrayListGene<>());
			}

			for (int i = 0; i < transforms.size(); i++) {
				for (int j = 0; j < source.length(); j++) {
					ArrayListGene g = (ArrayListGene) destination.get(j);
					Function<Gene<I>, Producer<O>> transform = transforms.get(i);
					Gene<I> input = source.valueAt(j);
					g.add(protein -> transform.apply(input));
				}
			}

			this.destination = IntStream.range(0, destination.size())
					.mapToObj(i -> assemble(i, destination.valueAt(i)))
					.collect(Collectors.toCollection(ArrayListChromosome::new));
		});

		prepare.get().run();
		return process();
	}

	@Override
	public Supplier<Runnable> tick() {
		return destination.stream()
				.flatMap(g -> IntStream.range(0, g.length()).mapToObj(g::valueAt))
				.map(f -> f instanceof TemporalFactor ? (TemporalFactor) f : null)
				.filter(Objects::nonNull)
				.map(Temporal::tick)
				.collect(OperationList.collector());
	}

	public TemporalList getTemporals() {
		return destination.stream()
				.flatMap(g -> IntStream.range(0, g.length()).mapToObj(g::valueAt))
				.map(f -> f instanceof Temporal ? (Temporal) f : null)
				.filter(Objects::nonNull)
				.collect(TemporalList.collector());
	}

	protected abstract Supplier<Runnable> process();

	protected Gene<O> assemble(int pos, Gene<O> transformed) {
		ArrayListGene<O> result = new ArrayListGene<>();

		for (int i = 0; i < getFactorCount(); i++) {
			result.add(factor(pos, i, transformed));
		}

		return result;
	}

	public abstract int getFactorCount();

	protected abstract Factor<O> factor(int pos, int factor, Gene<O> gene);

	@Override
	public Gene<O> valueAt(int pos) { return destination.valueAt(pos); }

	@Override
	public int length() { return destination.length(); }
}
