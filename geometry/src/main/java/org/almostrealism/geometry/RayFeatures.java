/*
 * Copyright 2022 Michael Murray
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
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.geometry.computations.RayExpressionComputation;
import io.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface RayFeatures extends VectorFeatures {

	default ExpressionComputation<Ray> v(Ray value) { return value(value); }

	default ExpressionComputation<Ray> value(Ray value) {
		return ExpressionComputation.fixed(value, Ray.postprocessor());
	}

	default ExpressionComputation<Ray> ray(double x, double y, double z, double dx, double dy, double dz) {
		return value(new Ray(new Vector(x, y, z), new Vector(dx, dy, dz)));
	}

	default RayExpressionComputation ray(Supplier<Evaluable<? extends Vector>> origin,
											Supplier<Evaluable<? extends Vector>> direction) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 6).forEach(i -> comp.add(args -> args.get(1 + i / 3).getValueAt(i % 3)));
		return new RayExpressionComputation(comp, (Supplier) origin, (Supplier) direction);
	}

	default ExpressionComputation<Ray> ray(IntFunction<Double> values) {
		return ray(values.apply(0), values.apply(1), values.apply(2),
				values.apply(3), values.apply(4), values.apply(5));
	}

	default ExpressionComputation<Vector> origin(Supplier<Evaluable<? extends Ray>> r) {
		return new ExpressionComputation<Vector>(List.of(
				args -> args.get(1).getValueAt(0),
				args -> args.get(1).getValueAt(1),
				args -> args.get(1).getValueAt(2)),
				(Supplier) r).setPostprocessor(Vector.postprocessor());
	}

	default ExpressionComputation<Vector> direction(Supplier<Evaluable<? extends Ray>> r) {
		return new ExpressionComputation<Vector>(List.of(
				args -> args.get(1).getValueAt(3),
				args -> args.get(1).getValueAt(4),
				args -> args.get(1).getValueAt(5)),
				(Supplier) r).setPostprocessor(Vector.postprocessor());
	}

	default ExpressionComputation<Vector> pointAt(Supplier<Evaluable<? extends Ray>> r, Supplier<Evaluable<? extends Scalar>> t) {
		return vector(add(origin(r), scalarMultiply(direction(r), t)));
	}

	default ExpressionComputation<Scalar> oDoto(Supplier<Evaluable<? extends Ray>> r) { return dotProduct(origin(r), origin(r)); }

	default ExpressionComputation<Scalar> dDotd(Supplier<Evaluable<? extends Ray>> r) { return dotProduct(direction(r), direction(r)); }

	default ExpressionComputation<Scalar> oDotd(Supplier<Evaluable<? extends Ray>> r) { return dotProduct(origin(r), direction(r)); }

	default RayExpressionComputation transform(TransformMatrix t, Supplier<Evaluable<? extends Ray>> r) {
		return ray(TransformMatrixFeatures.getInstance().transformAsLocation(t, origin(r)), TransformMatrixFeatures.getInstance().transformAsOffset(t, direction(r)));
	}

	static RayFeatures getInstance() {
		return new RayFeatures() { };
	}
}
