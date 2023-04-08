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

package org.almostrealism.geometry.computations;

import io.almostrealism.expression.Expression;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducerBase;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.DynamicCollectionProducerComputationAdapter;
import org.almostrealism.geometry.Ray;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryData;

import java.util.function.IntFunction;
import java.util.function.Supplier;

@Deprecated
public class RayOrigin extends DynamicCollectionProducerComputationAdapter<Ray, Vector> implements VectorProducerBase {
	private Expression<Double> value[];
	private boolean isStatic;

	public RayOrigin(Supplier<Evaluable<? extends Ray>> r) {
		super(new TraversalPolicy(3), r);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				return getArgument(1).valueAt(pos);
			} else {
				return value[pos];
			}
		};
	}

	 /**
	 * Returns true if the {@link Ray} {@link Evaluable} is static.
	 */
	@Override
	public boolean isStatic() { return !isVariableRef() && isStatic; }

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = new Expression[3];

			for (int i = 0; i < value.length; i++) {
				value[i] = getInputValue(1, i);
				if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			isStatic = getInputProducer(1).isStatic();

			absorbVariables(getInputProducer(1));
		}
	}

	 @Override
	 public Vector postProcessOutput(MemoryData output, int offset) {
		 return new Vector(output, offset);
	 }
}
