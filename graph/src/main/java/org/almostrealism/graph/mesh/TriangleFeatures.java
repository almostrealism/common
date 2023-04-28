/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.graph.mesh;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.computations.VectorExpressionComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicExpressionComputation;
import org.almostrealism.collect.computations.ExpressionComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface TriangleFeatures extends VectorFeatures {


	default VectorExpressionComputation abc(Supplier<Evaluable<? extends PackedCollection<?>>> t) {
		return vector(t, 0);
	}

	default VectorExpressionComputation def(Supplier<Evaluable<? extends PackedCollection<?>>> t) {
		return vector(t, 1);
	}

	default VectorExpressionComputation jkl(Supplier<Evaluable<? extends PackedCollection<?>>> t) {
		return vector(t, 2);
	}

	default VectorExpressionComputation normal(Supplier<Evaluable<? extends PackedCollection<?>>> t) {
		return vector(t, 4);
	}

	default ExpressionComputation<PackedCollection<Vector>> triangle(Supplier<Evaluable<? extends PackedCollection<?>>> points) {
		return triangle(
				point(points, 0),
				point(points, 1),
				point(points, 2));
	}

	default ExpressionComputation<PackedCollection<Vector>> triangle(Producer<Vector> p1,
																	 Producer<Vector> p2,
																	 Producer<Vector> p3) {
		Producer<Vector> abc = subtract(p2, p1);
		Producer<Vector> def = subtract(p3, p1);
		Supplier jkl = p1;
		return triangle(abc, def, jkl, crossProduct(abc, def).normalize());
	}

	default ExpressionComputation<PackedCollection<Vector>> triangle(Supplier<Evaluable<? extends Vector>> abc,
																	 Supplier<Evaluable<? extends Vector>> def,
																	 Supplier<Evaluable<? extends Vector>> jkl,
																	 Supplier<Evaluable<? extends Vector>> normal) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 12).forEach(i -> expression.add(args -> args.get(i / 3 + 1).getValue(i % 3)));
		return new ExpressionComputation<>(shape(4, 3), expression, (Supplier) abc, (Supplier) def, (Supplier) jkl, (Supplier) normal);
	}

	default ExpressionComputation<Vector> point(Supplier<Evaluable<? extends PackedCollection<?>>> points, int index) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> expression.add(args -> args.get(1).getValue(index * 3 + i)));
		return new ExpressionComputation<>(shape(3), expression, (Supplier) points);
	}

	default ExpressionComputation<PackedCollection<Vector>> points(Supplier<Evaluable<? extends Vector>> p1,
										 Supplier<Evaluable<? extends Vector>> p2,
										 Supplier<Evaluable<? extends Vector>> p3) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 9).forEach(i -> expression.add(args -> args.get(i / 3 + 1).getValue(i % 3)));
		return new ExpressionComputation<>(expression, (Supplier) p1, (Supplier) p2, (Supplier) p3);
		// TODO  return new DynamicExpressionComputation<>(shape(9), (args, index) -> args[???].getValueAt(index.mod(3)),
		// TODO            (Supplier) p1, (Supplier) p2, (Supplier) p3);
	}

	static TriangleFeatures getInstance() {
		return new TriangleFeatures() { };
	}
}
