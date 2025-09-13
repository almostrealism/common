/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.ExpressionComputation;
import io.almostrealism.relation.Evaluable;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public interface RayFeatures extends VectorFeatures {

	default CollectionProducer<Ray> v(Ray value) { return value(value); }

	default CollectionProducer<Ray> value(Ray value) {
		return DefaultTraversableExpressionComputation.fixed(value, Ray.postprocessor());
	}

	default CollectionProducer<Ray> ray(double x, double y, double z, double dx, double dy, double dz) {
		return value(new Ray(new Vector(x, y, z), new Vector(dx, dy, dz)));
	}

	default CollectionProducer<Ray> ray(Producer<Vector> origin, Producer<Vector> direction) {
		return concat(shape(6), (Producer) origin, (Producer) direction);
	}

	default CollectionProducer<Ray> ray(IntFunction<Double> values) {
		return ray(values.apply(0), values.apply(1), values.apply(2),
				values.apply(3), values.apply(4), values.apply(5));
	}

	default ExpressionComputation<Vector> origin(Supplier<Evaluable<? extends Ray>> r) {
		return (ExpressionComputation<Vector>) new ExpressionComputation<Vector>(List.of(
				args -> args.get(1).getValueRelative(0),
				args -> args.get(1).getValueRelative(1),
				args -> args.get(1).getValueRelative(2)),
				(Supplier) r).setPostprocessor(Vector.postprocessor());
	}

	default ExpressionComputation<Vector> direction(Supplier<Evaluable<? extends Ray>> r) {
		return (ExpressionComputation<Vector>) new ExpressionComputation<Vector>(List.of(
				args -> args.get(1).getValueRelative(3),
				args -> args.get(1).getValueRelative(4),
				args -> args.get(1).getValueRelative(5)),
				(Supplier) r).setPostprocessor(Vector.postprocessor());
	}

	default CollectionProducer<Vector> pointAt(Supplier<Evaluable<? extends Ray>> r, Producer<Scalar> t) {
		return vector(add(origin(r), scalarMultiply(direction(r), t)));
	}

	default CollectionProducer<Scalar> oDoto(Supplier<Evaluable<? extends Ray>> r) { return dotProduct(origin(r), origin(r)); }

	default CollectionProducer<Scalar> dDotd(Supplier<Evaluable<? extends Ray>> r) { return dotProduct(direction(r), direction(r)); }

	default CollectionProducer<Scalar> oDotd(Supplier<Evaluable<? extends Ray>> r) { return dotProduct(origin(r), direction(r)); }

	default CollectionProducer<Ray> transform(TransformMatrix t, Supplier<Evaluable<? extends Ray>> r) {
		return ray(
				TransformMatrixFeatures.getInstance().transformAsLocation(t, origin(r)),
				TransformMatrixFeatures.getInstance().transformAsOffset(t, direction(r)));
	}

	static RayFeatures getInstance() {
		return new RayFeatures() { };
	}
}
