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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.computations.StaticScalarComputation;
import org.almostrealism.geometry.computations.Sine;

import java.util.function.Supplier;

public interface GeometryFeatures {
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
}
