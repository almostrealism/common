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

import io.almostrealism.expression.Expression;
import org.almostrealism.algebra.Pair;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.PairProducer;
import org.almostrealism.algebra.PairProducerBase;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.DynamicCollectionProducerComputationAdapter;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import org.almostrealism.hardware.MemoryData;

import java.util.function.IntFunction;
import java.util.function.Supplier;

@Deprecated
public class PairFromPairBank extends DynamicCollectionProducerComputationAdapter<PackedCollection<Pair<?>>, Pair<?>> implements PairProducerBase {
	public PairFromPairBank(Supplier<Evaluable<? extends PackedCollection<Pair<?>>>> bank, Supplier<Evaluable<? extends Scalar>> index) {
		super(new TraversalPolicy(2), bank, (Supplier) index);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (pos == 0) {
				if (getArgument(2).isStatic()) {
					return getArgument(1).get("2 * floor(" + getInputValue(2, 0).getExpression() + ")");
				} else {
					return getArgument(1).get("2 * floor(" + getInputValue(2, 0).getExpression() + ")", getArgument(2));
				}
			} else if (pos == 1) {
				if (getArgument(2).isStatic()) {
					return getArgument(1).get("2 * floor(" + getInputValue(2, 0).getExpression() + ") + 1");
				} else {
					return getArgument(1).get("2 * floor(" + getInputValue(2, 0).getExpression() + ") + 1", getArgument(2));
				}
			} else {
				throw new IllegalArgumentException();
			}
		};
	}

	@Override
	public Pair postProcessOutput(MemoryData output, int offset) {
		return new Pair(output, offset);
	}
}
