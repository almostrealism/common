/*
 * Copyright 2021 Michael Murray
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
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.computations.StaticScalarComputation;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.geometry.computations.Sine;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface GeometryFeatures extends CollectionFeatures {
	double PI = Math.PI;
	double TWO_PI = 2 * PI;

	default Sine sin(Supplier<Evaluable<? extends Scalar>> input) {
		return new Sine(input);
	}

	default Sine sinw(Supplier<Evaluable<? extends Scalar>> input, Supplier<Evaluable<? extends Scalar>> wavelength) {
		return sin(new StaticScalarComputation(new Scalar(TWO_PI)).multiply(input).divide(wavelength));
	}

	default ScalarProducer sinw(Supplier<Evaluable<? extends Scalar>> input, Supplier<Evaluable<? extends Scalar>> wavelength,
								Supplier<Evaluable<? extends Scalar>> amp) {
		return sin(new StaticScalarComputation(new Scalar(TWO_PI)).multiply(input).divide(wavelength)).multiply(amp);
	}

	default ExpressionComputation _sin(Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		Function<List<MultiExpression<Double>>, Expression<Double>> exp = args -> new io.almostrealism.expression.Sine(args.get(0).getValue(0));
		return new ExpressionComputation(List.of(exp), input);
	}

	default Sine _sinw(Supplier<Evaluable<? extends PackedCollection<?>>> input, Supplier<Evaluable<? extends PackedCollection<?>>> wavelength) {
		return sin(c(TWO_PI)._multiply(input)._divide(wavelength));
	}

	default ExpressionComputation<PackedCollection<?>> _sinw(Supplier<Evaluable<? extends PackedCollection<?>>> input, Supplier<Evaluable<? extends PackedCollection<?>>> wavelength,
								Supplier<Evaluable<? extends PackedCollection<?>>> amp) {
		return _sin(c(TWO_PI)._multiply(input)._divide(wavelength))._multiply(amp);
	}
}
