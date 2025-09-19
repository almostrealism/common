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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

import java.util.function.IntFunction;

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

	default CollectionProducer<Vector> origin(Producer<Ray> r) {
		return subset(shape(3), r, 0);
	}

	default CollectionProducer<Vector> direction(Producer<Ray> r) {
		return subset(shape(3), r, 3);
	}

	default CollectionProducer<Vector> pointAt(Producer<Ray> r, Producer<PackedCollection<?>> t) {
		return vector(direction(r).multiply(t).add(origin(r)));
	}

	default CollectionProducer<Scalar> oDoto(Producer<Ray> r) { return dotProduct(origin(r), origin(r)); }

	default CollectionProducer<Scalar> dDotd(Producer<Ray> r) { return dotProduct(direction(r), direction(r)); }

	default CollectionProducer<Scalar> oDotd(Producer<Ray> r) { return dotProduct(origin(r), direction(r)); }

	default CollectionProducer<Ray> transform(TransformMatrix t, Producer<Ray> r) {
		return ray(
				TransformMatrixFeatures.getInstance().transformAsLocation(t, origin(r)),
				TransformMatrixFeatures.getInstance().transformAsOffset(t, direction(r)));
	}

	static RayFeatures getInstance() {
		return new RayFeatures() { };
	}
}
