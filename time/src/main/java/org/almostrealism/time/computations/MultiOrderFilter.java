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

package org.almostrealism.time.computations;

import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

import java.util.List;

public class MultiOrderFilter extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
	private int filterOrder;

	public MultiOrderFilter(TraversalPolicy shape, Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> coefficients) {
		super("multiOrderFilter", shape, new Producer[] { series, coefficients });

		TraversalPolicy seriesShape = CollectionFeatures.getInstance().shape(series);
		TraversalPolicy coeffShape = CollectionFeatures.getInstance().shape(coefficients);

		if (seriesShape.getSizeLong() <= 1) {
			throw new UnsupportedOperationException();
		}

		if (coeffShape.getSizeLong() <= 1) {
			throw new UnsupportedOperationException();
		}

		this.filterOrder = coeffShape.getSize() - 1;
	}

	@Override
	public Scope<PackedCollection<?>> getScope(KernelStructureContext context) {
		Scope<PackedCollection<?>> scope = super.getScope(context);

		CollectionVariable output = getCollectionArgumentVariable(0);
		CollectionVariable input = getCollectionArgumentVariable(1);
		CollectionVariable coefficients = getCollectionArgumentVariable(2);

		Expression result = scope.declareDouble("result", e(0.0));

		Repeated loop = new Repeated<>();
		{
			InstanceReference i = Variable.integer("i").ref();
			loop.setIndex(i.getReferent());
			loop.setCondition(i.lessThanOrEqual(e(filterOrder)));
			loop.setInterval(e(1));

			Scope<?> body = new Scope<>();
			{
				Expression index = body.declareInteger("index", kernel(context).add(i.subtract(e(filterOrder / 2))));

				Expression coeff = coefficients.getShape().getDimensions() == 1 ?
						coefficients.getValueAt(i) : coefficients.getValue(kernel(), i);

				body.addCase(index.greaterThanOrEqual(e(0)).and(index.lessThan(input.length())),
						result.assign(result.add(input.getValueAt(index).multiply(coeff))));
			}

			loop.add(body);
		}

		scope.add(loop);


		Scope outScope = new Scope();
		{
			outScope.getStatements().add(output.getValueAt(kernel(context)).assign(result));
		}

		scope.add(outScope);

		return scope;
	}

	@Override
	public MultiOrderFilter generate(List<Process<?, ?>> children) {
		return new MultiOrderFilter(getShape(), (Producer) children.get(1), (Producer) children.get(2));
	}

	public static MultiOrderFilter create(Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> coefficients) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(series);
		if (shape.getTraversalAxis() != shape.getDimensions() - 1) {
			series = CollectionFeatures.getInstance().traverse(shape.getDimensions() - 1, series);
		}

		return new MultiOrderFilter(shape.traverseEach(), series, coefficients);
	}
}
