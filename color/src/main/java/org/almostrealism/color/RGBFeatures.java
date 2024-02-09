/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.computations.ExpressionComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface RGBFeatures extends ScalarFeatures {

	default CollectionProducer<RGB> v(RGB value) { return value(value); }

	default CollectionProducer<RGB> rgb(double r, double g, double b) { return value(new RGB(r, g, b)); }

	default CollectionProducer<RGB> rgb(Producer<RGB> rgb) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> comp.add(args -> args.get(1).getValueRelative(i)));
		return (ExpressionComputation<RGB>) new ExpressionComputation<>(comp, (Supplier) rgb).setPostprocessor(RGB.postprocessor());
	}

	default CollectionProducer<RGB> rgb(Supplier<Evaluable<? extends Scalar>> r, Supplier<Evaluable<? extends Scalar>> g, Supplier<Evaluable<? extends Scalar>> b) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> comp.add(args -> args.get(1 + i).getValueRelative(0)));
		return (ExpressionComputation<RGB>) new ExpressionComputation<>(comp, (Supplier) r, (Supplier) g, (Supplier) b).setPostprocessor(RGB.postprocessor());
	}

	default CollectionProducer<RGB> rgb(Scalar v) { return cfromScalar(v); }

	default CollectionProducer<RGB> rgb(double v) { return cfromScalar(v); }

	default CollectionProducer<RGB> white() { return rgb(1.0, 1.0, 1.0); }
	default CollectionProducer<RGB> black() { return rgb(0.0, 0.0, 0.0); }

	default CollectionProducer<RGB> value(RGB value) {
		return ExpressionComputation.fixed(value, RGB.postprocessor());
	}

	default CollectionProducer<RGB> cfromScalar(Supplier<Evaluable<? extends Scalar>> value) {
		return rgb(value, value, value);
	}

	default CollectionProducer<RGB> cfromScalar(Scalar value) {
		return cfromScalar(ScalarFeatures.of(value));
	}

	default CollectionProducer<RGB> cfromScalar(double value) {
		return cfromScalar(new Scalar(value));
	}

	default Producer<RGB> attenuation(double da, double db, double dc, Producer<RGB> color, Producer<Scalar> distanceSq) {
		return multiply((Producer) color, (Producer) cfromScalar(multiply(v(da), distanceSq)
				.add(v(db).multiply(scalarPow(distanceSq, scalar(0.5))))
				.add(v(dc))));
	}


	static RGBFeatures getInstance() { return new RGBFeatures() {}; }
}
