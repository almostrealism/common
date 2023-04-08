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
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.VectorEvaluable;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.DefaultVectorEvaluable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.computations.ScalarBankExpressionComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface TriangleDataFeatures extends VectorFeatures {
	default VectorEvaluable abc(Evaluable<TriangleData> t) {
		return (VectorEvaluable) abc(() -> t).get();
	}

	default VectorProducer abc(Supplier<Evaluable<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.ABC);
	}

	default VectorEvaluable def(Evaluable<TriangleData> t) {
		return (VectorEvaluable) def(() -> t).get();
	}

	default VectorProducer def(Supplier<Evaluable<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.DEF);
	}

	default VectorEvaluable jkl(Evaluable<TriangleData> t) {
		return (VectorEvaluable) jkl(() -> t).get();
	}

	default VectorProducer jkl(Supplier<Evaluable<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.JKL);
	}

	default VectorEvaluable normal(Evaluable<TriangleData> t) {
		return (VectorEvaluable) normal(() -> t).get();
	}

	default VectorProducer normal(Supplier<Evaluable<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.NORMAL);
	}

	default TriangleDataProducer triangle(Supplier<Evaluable<? extends PackedCollection<?>>> points) {
		return triangle(
				point(points, 0),
				point(points, 1),
				point(points, 2));
	}

	default TriangleDataProducer triangle(Supplier<Evaluable<? extends Vector>> p1,
										  Supplier<Evaluable<? extends Vector>> p2,
										  Supplier<Evaluable<? extends Vector>> p3) {
		return new TriangleDataFromVectors(subtract(p2, p1), subtract(p3, p1), p1);
	}

	default ExpressionComputation<Vector> point(Supplier<Evaluable<? extends PackedCollection<?>>> points, int index) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> expression.add(args -> args.get(1).getValue(index * 3 + i)));
		return new ExpressionComputation<>(expression, (Supplier) points);
	}

	default ExpressionComputation<PackedCollection<Vector>> points(Supplier<Evaluable<? extends Vector>> p1,
										 Supplier<Evaluable<? extends Vector>> p2,
										 Supplier<Evaluable<? extends Vector>> p3) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 9).forEach(i -> expression.add(args -> args.get(i / 3 + 1).getValue(i % 3)));
		return new ExpressionComputation<>(expression, (Supplier) p1, (Supplier) p2, (Supplier) p3);
	}

	static TriangleDataFeatures getInstance() {
		return new TriangleDataFeatures() { };
	}
}
