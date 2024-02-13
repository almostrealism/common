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

import io.almostrealism.code.OperationMetadata;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Cases;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

import java.util.function.Supplier;

public class FourierTransform extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
	private static final int SIZE = 1024;

	public FourierTransform(int bins, Producer<PackedCollection<?>> input) {
		super(new TraversalPolicy(bins), input);
	}

	@Override
	public Scope<PackedCollection<?>> getScope() {
		HybridScope<PackedCollection<?>> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "FourierTransform"));

		ArrayVariable<Double> input =  new ArrayVariable<>(this, Double.class, "input", e(SIZE));
		ArrayVariable<Double> output = new ArrayVariable<>(this, Double.class, "output", e(SIZE));

		Scope<?> radix2 = radix2(output, input);
		scope.getRequiredScopes().add(radix2);
		scope.getStatements().add(radix2.call(e(SIZE), e(0), e(0)));

		return scope;
	}

	protected Scope<?> radix2(ArrayVariable<?> output, ArrayVariable<?> input) {
		Variable<Integer, ?> len = Variable.integer("len");
		Variable<Integer, ?> inverseTransform = Variable.integer("inverseTransform");
		Variable<Integer, ?> isFirstSplit = Variable.integer("isFirstSplit");

		OperationMetadata radix2Metadata = new OperationMetadata
				(getFunctionName() + "_radix2", "Radix 2");
		Scope<PackedCollection<?>> radix2 = new Scope<>(getFunctionName() + "_radix2", radix2Metadata);
		radix2.getParameters().add(len);
		radix2.getParameters().add(inverseTransform);
		radix2.getParameters().add(isFirstSplit);

		ArrayVariable<Double> even = radix2.declareArray(this, "even", e(SIZE / 2));
		ArrayVariable<Double> odd = radix2.declareArray(this, "odd", e(SIZE / 2));
		ArrayVariable<Double> evenFft = radix2.declareArray(this, "evenFft", e(SIZE / 2));
		ArrayVariable<Double> oddFft = radix2.declareArray(this, "oddFft", e(SIZE / 2));

		Cases cases = new Cases<>(); {
			Scope<?> main = cases.addCase(len.ref().greaterThanOrEqual(e(2)), new Scope<>());
			Expression halfN = main.declareInteger("halfN", len.ref().divide(e(2)));
			Expression angle = main.declareDouble("angle", e(2).multiply(pi()).divide(len.ref()));
			main.addCase(inverseTransform.ref().eq(e(0)), assign(angle, angle.minus()));

			Repeated evenOdd = new Repeated<>(); {
				InstanceReference k = Variable.integer("k").ref();
				evenOdd.setIndex(k.getReferent());
				evenOdd.setCondition(k.lessThan(halfN));
				evenOdd.setInterval(e(1));

				Scope<?> body = new Scope(); {
					Expression<?> kPlusHalfN = body.declareInteger("kPlusHalfN", k.add(halfN));
					Expression<?> angleK = body.declareDouble("angleK", k.multiply(k));
					Expression<?> omegaR = body.declareDouble("omegaR", angleK.cos());
					Expression<?> omegaI = body.declareDouble("omegaI", angleK.sin());

					Expression k2 = k.multiply(2);
					Expression kPlusHalfN2 = kPlusHalfN.multiply(2);

					body.assign(even.valueAt(k2), input.valueAt(k2).add((Expression) input.valueAt(kPlusHalfN2)));
					body.assign(even.valueAt(k2.add(1)), input.valueAt(k2.add(1)).add((Expression) input.valueAt(kPlusHalfN2.add(1))));

					Expression inKMinusInKPlusHalfnR = input.valueAt(k2).subtract((Expression) input.valueAt(kPlusHalfN2));

					evenOdd.add(body);
				}

				main.add(evenOdd);
			}

			Scope<?> base = cases.addCase(null, new Scope<>()); {

			}

			radix2.add(cases);
		}

		return radix2;
	}
}
