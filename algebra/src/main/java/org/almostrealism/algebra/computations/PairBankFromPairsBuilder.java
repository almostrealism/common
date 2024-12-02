/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factory;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBankFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.RelativeTraversableProducerComputation;

import java.util.Collection;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class PairBankFromPairsBuilder extends RelativeTraversableProducerComputation<Pair<?>, PackedCollection<Pair<?>>>
									implements Factory<Producer<PackedCollection<Pair<?>>>>, PairBankFeatures {
	private Producer<Pair<?>> producers[];

	public PairBankFromPairsBuilder(int count) {
		super(new TraversalPolicy(count, 2).traverse(0), new Producer[0]);
		producers = new Producer[count];
	}

	protected PairBankFromPairsBuilder(TraversalPolicy shape, Producer<Pair<?>>[] producers) {
		super(shape, new Producer[0]);
		this.producers = producers;
	}

	public Producer<Pair<?>> get(int index) {
		return producers[index];
	}

	public void set(int index, Producer<Pair<?>> value) {
		producers[index] = value;
	}

	public int getProducerCount() { return producers.length; }

	@Override
	public boolean isFixedCount() {
		return getChildren().stream().noneMatch(v -> v instanceof Countable && !((Countable) v).isFixedCount());
	}

	@Override
	public long getCountLong() {
		// TODO  Does this need to be based on the Producer array?
		return super.getCountLong();
	}

	@Override
	public Collection getChildren() {
		return List.of(producers);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		ScopeLifecycle.prepareArguments(Stream.of(producers), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		ScopeLifecycle.prepareScope(Stream.of(producers), manager, context);
	}

	@Override
	public Evaluable<PackedCollection<Pair<?>>> get() {
		return construct().get();
	}

	@Override
	public Producer<PackedCollection<Pair<?>>> construct() { return pairBank(producers); }

	@Override
	public PairBankFromPairsBuilder generate(List<Process<?, ?>> children) {
		return new PairBankFromPairsBuilder(getShape(), children.toArray(new Producer[0]));
	}

	private int arg(int index) { return index / 2; }
	private int pos(int index) { return index % 2; }

	public Expression<Double> getValue(List<ArrayVariable<Double>> args, int index) {
		return ((TraversableExpression) producers[arg(index)]).getValueAt(new IntegerConstant(pos(index)));
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
//		return i -> getExpression(producers[arg(i)]).get().getValue(pos(i));
		return i ->
			((TraversableExpression) producers[arg(i)]).getValueAt(new IntegerConstant(pos(i)));
	}
}
