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

package org.almostrealism.geometry;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.Sine;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducerComputationAdapter;
import org.almostrealism.collect.computations.ExpressionComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface GeometryFeatures extends CollectionFeatures {
	double PI = Math.PI;
	double TWO_PI = 2 * PI;

	@Deprecated
	default ScalarExpressionComputation sin(Producer<Scalar> input) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		expression.add(args -> new Sine(args.get(1).getValue(0)));
		expression.add(args -> args.get(1).getValue(1));
		return new ScalarExpressionComputation(expression, (Supplier) input);
	}

	default DynamicCollectionProducerComputationAdapter<Scalar, Scalar> sinw(Producer<Scalar> input, Producer<Scalar> wavelength,
				  Producer<Scalar> amp) {
		return multiply(sin(ScalarFeatures.of(new Scalar(TWO_PI)).multiply(input).divide(wavelength)), amp);
	}

	default ExpressionComputation _sin(Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		Function<List<MultiExpression<Double>>, Expression<Double>> exp = args -> new io.almostrealism.expression.Sine(args.get(1).getValue(0));
		return new ExpressionComputation(List.of(exp), input);
	}

	default DynamicCollectionProducerComputationAdapter<PackedCollection<?>, PackedCollection<?>> _sinw(Producer<PackedCollection<?>> input,
															 Producer<PackedCollection<?>> wavelength,
															 Producer<PackedCollection<?>> amp) {
		return _sin(c(TWO_PI).multiply(input).divide(wavelength)).multiply(amp);
	}
}
