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

		Scope<?> radix2 = radix2(scope.declareArray(this, "output", e(SIZE)),
				scope.declareArray(this, "input", e(SIZE)));
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

		Cases cases = new Cases<>();
		Scope<?> main = cases.addCase(len.ref().greaterThanOrEqual(e(2)), new Scope<>());
		Expression halfN = main.declareInteger("halfN", len.ref().divide(e(2)));
		Expression angle = main.declareDouble("angle", e(2).multiply(pi()).divide(len.ref()));
		main.addCase(inverseTransform.ref().eq(e(0)), assign(angle, angle.minus()));

		Repeated evenOdd = new Repeated<>();
		Variable k = Variable.integer("k");
		evenOdd.setIndex(k);
		evenOdd.setCondition(k.ref().lessThan(halfN));
		evenOdd.setInterval(e(1));
		main.add(evenOdd);

		Scope<?> base = cases.addCase(null, new Scope<>());
		radix2.add(cases);

		return radix2;
	}
}
