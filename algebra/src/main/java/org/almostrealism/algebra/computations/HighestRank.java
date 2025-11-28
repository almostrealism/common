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
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

/**
 * A computation that finds the highest-ranked (smallest non-zero) value in a distance array.
 *
 * <p>
 * {@link HighestRank} iterates through a distance/score array and identifies:
 * <ul>
 *   <li>The smallest value that is greater than epsilon (closest/best match)</li>
 *   <li>The index of that value</li>
 * </ul>
 * The result is returned as a {@link Pair} containing (value, index).
 *
 * <p>
 * This is commonly used in:
 * <ul>
 *   <li>Nearest neighbor search</li>
 *   <li>Finding highest confidence predictions</li>
 *   <li>Best match selection in pattern recognition</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Distance array: [0.0, 0.5, 0.3, 0.0, 0.8]
 * // Configuration: (5, epsilon)
 * Producer<PackedCollection> distances = ...;
 * Producer<Pair> config = pair(5.0, epsilon);
 *
 * HighestRank highestRank = new HighestRank(distances, config);
 * // Result: (0.3, 2) - value 0.3 at index 2 is the smallest non-zero distance
 * }</pre>
 *
 * @author  Michael Murray
 * @see Pair
 */
public class HighestRank extends CollectionProducerComputationBase<PackedCollection, Pair> {
	private int varIdx = 0;

	/**
	 * Creates a new HighestRank computation.
	 *
	 * @param distances  producer for the array of distance/score values
	 * @param conf  producer for configuration pair containing (count, epsilon)
	 */
	public HighestRank(Producer<PackedCollection> distances, Producer<Pair> conf) {
		super("highestRank", CollectionFeatures.getInstance().shape(distances), distances, (Producer) conf);
		setPostprocessor(Pair.postprocessor());
	}

	/**
	 * Generates unique variable names to avoid collisions in generated code.
	 *
	 * @param name  base variable name
	 * @return unique variable name with index suffix
	 */
	private String varName(String name) {
		return name + "_" + varIdx++;
	}

	/**
	 * Generates the scope containing the highest-rank search logic.
	 *
	 * <p>
	 * The algorithm:
	 * <ol>
	 *   <li>Initialize closest = -1, closestIndex = -1</li>
	 *   <li>Iterate through all distance values</li>
	 *   <li>Update closest if:
	 *     <ul>
	 *       <li>value >= epsilon AND no closest found yet, OR</li>
	 *       <li>value >= epsilon AND value < current closest</li>
	 *     </ul>
	 *   </li>
	 *   <li>Return (closest, closestIndex) or (-1, -1) if no match found</li>
	 * </ol>
	 * </p>
	 *
	 * @param context  the kernel structure context
	 * @return the scope containing the search logic
	 */
	@Override
	public Scope<Pair> getScope(KernelStructureContext context) {
		HybridScope<Pair> scope = new HybridScope<>(this);

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
									distances.reference(i.ref()));

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
