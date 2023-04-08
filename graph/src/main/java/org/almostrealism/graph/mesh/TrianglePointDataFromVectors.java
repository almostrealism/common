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

package org.almostrealism.graph.mesh;

import io.almostrealism.expression.Expression;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.DynamicProducerForMemoryData;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class TrianglePointDataFromVectors extends DynamicProducerComputationAdapter<Vector, TrianglePointData> implements TrianglePointDataProducer {
	private Expression<Double> value[];

	public TrianglePointDataFromVectors(Supplier<Evaluable<? extends Vector>> p1, Supplier<Evaluable<? extends Vector>> p2,
								   Supplier<Evaluable<? extends Vector>> p3) {
		super(9, new DynamicProducerForMemoryData(() -> Vector.bank(3), TrianglePointDataBank::new), TrianglePointDataBank::new, p1, p2, p3);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null || value[pos] == null) {
				return getArgument((pos / 3) + 1).valueAt(pos % 3);
			} else {
				return value[pos];
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null) {
			value = new Expression[12];

			i: for (int i = 1; i < getArgumentVariables().size(); i++) {
				// Ignore those that are not of a compactable kind
				if (getArgumentVariables().get(i).getProducer() instanceof DynamicProducerComputationAdapter == false) {
					continue i;
				}

				DynamicProducerComputationAdapter child = (DynamicProducerComputationAdapter) getArgumentVariables().get(i).getProducer();

				// Ignore those that are more than just a value returned from getValue
				if (!child.isValueOnly()) {
					continue i;
				}

				// If it is just a value, include it in the compacted version
				int index = 3 * (i - 1);
				value[index] = child.getValue(0);
				value[index + 1] = child.getValue(1);
				value[index + 2] = child.getValue(2);

				absorbVariables((Supplier) child);
			}

			// Check for illegal values
			for (int i = 0; i < value.length; i++) {
				if (value[i] != null && value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}
		}
	}
}
