/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.color;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.color.computations.StaticRGBComputation;
import org.almostrealism.color.computations.ColorProduct;
import org.almostrealism.color.computations.RGBFromScalars;

import java.util.function.Supplier;

public interface RGBFeatures extends ScalarFeatures {

	static RGBProducer of(RGB value) {
		return new StaticRGBComputation(value);
	}

	default RGBProducer v(RGB value) { return value(value); }

	default RGBProducer rgb(double r, double g, double b) { return value(new RGB(r, g, b)); }

	default RGBProducer rgb(Supplier<Evaluable<? extends Scalar>> r, Supplier<Evaluable<? extends Scalar>> g, Supplier<Evaluable<? extends Scalar>> b) {
		return new RGBFromScalars(r, g, b);
	}

	default RGBProducer rgb(Scalar v) { return cfromScalar(v); }

	default RGBProducer rgb(double v) { return cfromScalar(v); }

	default RGBProducer value(RGB value) {
		return new StaticRGBComputation(value);
	}

	default RGBEvaluable cmultiply(Evaluable<RGB> a, Evaluable<RGB> b) {
		return (RGBEvaluable) cmultiply(() -> a, () -> b).get();
	}

	default RGBProducer cmultiply(Supplier<Evaluable<? extends RGB>> a, Supplier<Evaluable<? extends RGB>> b) {
		return new ColorProduct(a, b);
	}

	default RGBEvaluable cscalarMultiply(Evaluable<RGB> a, Evaluable<Scalar> b) {
		return (RGBEvaluable) cscalarMultiply(() -> a, () -> b).get();
	}

	default RGBProducer cscalarMultiply(Supplier<Evaluable<? extends RGB>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return cmultiply(a, cfromScalar(b));
	}

	default RGBEvaluable cminus(Evaluable<RGB> p) {
		return (RGBEvaluable) cminus(() -> p).get();
	}

	default RGBProducer cminus(Supplier<Evaluable<? extends RGB>> p) {
		return cmultiply(p, cfromScalar(ScalarFeatures.minusOne()));
	}

	default RGBProducer cfromScalar(Supplier<Evaluable<? extends Scalar>> value) {
		return new RGBFromScalars(value, value, value);
	}

	default RGBProducer cfromScalar(Scalar value) {
		return cfromScalar(ScalarFeatures.of(value));
	}

	default RGBProducer cfromScalar(double value) {
		return cfromScalar(new Scalar(value));
	}

	default Producer<RGB> attenuation(double da, double db, double dc, Producer<RGB> color, Producer<Scalar> distanceSq) {
		return multiply((Producer) color, (Producer) cfromScalar(multiply(v(da), distanceSq)
				.add(v(db).multiply(pow(distanceSq, scalar(0.5))))
				.add(v(dc))));
	}


	static RGBFeatures getInstance() { return new RGBFeatures() {}; }
}
