/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.algebra.computations;

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

public class HighestRank extends CollectionProducerComputationBase<PackedCollection<Scalar>, Pair<?>> {
	private int varIdx = 0;

	public HighestRank(Producer<PackedCollection<Scalar>> distances, Producer<Pair<?>> conf) {
		super("highestRank", CollectionFeatures.getInstance().shape(distances), distances, (Producer) conf);
		setPostprocessor(Pair.postprocessor());
	}

	private String varName(String name) {
		return name + "_" + varIdx++;
	}

	@Override
	public Scope<Pair<?>> getScope(KernelStructureContext context) {
		HybridScope<Pair<?>> scope = new HybridScope<>(this);

		ArrayVariable distances = getArgument(1);
		ArrayVariable conf = getArgument(2);

		Expression<Double> closest = scope.declareDouble(varName("closest"), e(-1.0));
		Expression<Double> closestIndex = scope.declareInteger(varName("closestIndex"), e(-1));

		Expression<Double> count = conf.valueAt(0);
		Expression<Double> eps = epsilon();

		Variable i = new Variable(varName("i"), Integer.class);
		Repeated loop = new Repeated<>(i, i.ref().lessThan(count));
		Scope<?> loopBody = new Scope<>(); {
			Expression<Double> value = loopBody.declareDouble(varName("value"),
									distances.reference(i.ref().multiply(2)));

			Scope updateClosest = new Scope<>();
			updateClosest.assign(closest, value);
			updateClosest.assign(closestIndex, i.ref());

			loopBody.addCase(value.greaterThanOrEqual(eps).and(closestIndex.eq(e(-1))), updateClosest);
			loopBody.addCase(value.greaterThanOrEqual(eps).and(value.lessThan(closest)), updateClosest);

			loop.add(loopBody);
		}

		scope.add(loop);

		Scope results = new Scope<>();
		results.assign(getArgument(0).valueAt(0),
				conditional(closestIndex.lessThan(e(0)), e(-1.0), closest));
		results.assign(getArgument(0).valueAt(1), closestIndex);

		scope.add(results);
		return scope;
	}
}
