/*
 * Copyright 2021 Michael Murray
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
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.relation.Compactable;
import io.almostrealism.relation.Factory;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import org.almostrealism.hardware.KernelizedEvaluable;

import java.util.Objects;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public class PairBankFromPairsBuilder extends DynamicProducerComputationAdapter<Pair, PairBank>
									implements Factory<PairBankFromPairs> {
	private Producer<Pair> producers[];

	public PairBankFromPairsBuilder(int count) {
		super(2 * count, () -> args -> new PairBank(count), null, new Producer[0]);
		producers = new Producer[count];
	}

	public Producer<Pair> get(int index) {
		return producers[index];
	}

	public void set(int index, Producer<Pair> value) {
		producers[index] = value;
	}

	public int getCount() { return producers.length; }

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		ScopeLifecycle.prepareArguments(Stream.of(producers), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		ScopeLifecycle.prepareScope(Stream.of(producers), manager);
	}

	@Override
	public synchronized void compact() {
		Stream.of(producers).filter(Objects::nonNull).forEach(p -> ((Compactable) p).compact());
	}

	@Override
	public KernelizedEvaluable<PairBank> get() { return construct().get(); }

	@Override
	public PairBankFromPairs construct() { return new PairBankFromPairs(producers); }

	private int arg(int index) { return index / 2; }
	private int pos(int index) { return index % 2; }

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> getExpression(producers[arg(i)]).get().getValue(pos(i));
	}
}
