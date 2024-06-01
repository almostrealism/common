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
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DynamicCollectionProducer;

public class MultiOrderFilter extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
	private int filterOrder;

	public MultiOrderFilter(TraversalPolicy shape, Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> coefficients) {
		super(null, shape, new Producer[] { series, coefficients });

		TraversalPolicy seriesShape = CollectionFeatures.getInstance().shape(series);

		if (seriesShape.getSizeLong() <= 1) {
			throw new UnsupportedOperationException();
		}

		this.filterOrder = shape(coefficients).length(0) - 1;
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
				Expression index = body.declareInteger("index", kernel().add(i.subtract(e(filterOrder / 2))));

				body.addCase(index.greaterThanOrEqual(e(0)).and(index.lessThan(input.length())),
						result.assign(result.add(input.getValueAt(index).multiply(coefficients.getValueAt(i)))));
			}

			loop.add(body);
		}

		scope.add(loop);


		Scope outScope = new Scope();
		{
			outScope.getStatements().add(output.getValueAt(kernel()).assign(result));
		}

		scope.add(outScope);

		return scope;
	}

	public static MultiOrderFilter createLowPass(Producer<PackedCollection<?>> series,
												 Producer<PackedCollection<?>> cutoff,
												 int sampleRate, int order) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(series);
		if (shape.getTraversalAxis() != shape.getDimensions() - 1) {
			series = CollectionFeatures.getInstance().traverse(shape.getDimensions() - 1, series);
		}

		return create(series, lowPassCoefficients(cutoff, sampleRate, order));
	}

	public static MultiOrderFilter createHighPass(Producer<PackedCollection<?>> series,
												  Producer<PackedCollection<?>> cutoff,
												  int sampleRate, int order) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(series);
		if (shape.getTraversalAxis() != shape.getDimensions() - 1) {
			series = CollectionFeatures.getInstance().traverse(shape.getDimensions() - 1, series);
		}

		return create(series, highPassCoefficients(cutoff, sampleRate, order));
	}

	public static MultiOrderFilter create(Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> coefficients) {
		TraversalPolicy shape = CollectionFeatures.getInstance().shape(series);
		if (shape.getTraversalAxis() != shape.getDimensions() - 1) {
			series = CollectionFeatures.getInstance().traverse(shape.getDimensions() - 1, series);
		}

		return new MultiOrderFilter(shape.traverseEach(), series, coefficients);
	}

	private static CollectionProducer<PackedCollection<?>> lowPassCoefficients(
																	Producer<PackedCollection<?>> cutoff,
																	int sampleRate, int filterOrder) {
		return new DynamicCollectionProducer<>(new TraversalPolicy(filterOrder + 1), args -> {
			double[] coefficients = new double[filterOrder + 1];
			double normalizedCutoff = 2 * cutoff.get().evaluate().toDouble(0) / sampleRate;

			for (int i = 0; i <= filterOrder; i++) {
				if (i == filterOrder / 2) {
					coefficients[i] = normalizedCutoff;
				} else {
					int k = i - filterOrder / 2;
					coefficients[i] = Math.sin(Math.PI * k * normalizedCutoff) / (Math.PI * k);
				}

				// Hamming window
				coefficients[i] *= 0.54 - 0.46 * Math.cos(2 * Math.PI * i / filterOrder);
			}

			return PackedCollection.of(coefficients);
		}, false);
	}

	private static CollectionProducer<PackedCollection<?>> highPassCoefficients(
																	Producer<PackedCollection<?>> cutoff,
																	int sampleRate, int filterOrder) {
		CollectionProducer<PackedCollection<?>> lp = lowPassCoefficients(cutoff, sampleRate, filterOrder);

		return new DynamicCollectionProducer<>(lp.getShape(), args -> {
			double lowPassCoefficients[] = lp.get().evaluate().toArray();

			double[] highPassCoefficients = new double[filterOrder + 1];
			for (int i = 0; i <= filterOrder; i++) {
				highPassCoefficients[i] = ((i == filterOrder / 2) ? 1.0 : 0.0) - lowPassCoefficients[i];
			}

			return PackedCollection.of(highPassCoefficients);
		}, false);
	}
}
