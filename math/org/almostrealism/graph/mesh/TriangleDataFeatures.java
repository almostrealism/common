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

package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.VectorEvaluable;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.DefaultVectorEvaluable;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

import static org.almostrealism.util.Ops.ops;

public interface TriangleDataFeatures extends VectorFeatures {
	default VectorEvaluable abc(Evaluable<TriangleData> t) {
		return new DefaultVectorEvaluable(abc(() -> t));
	}

	default VectorProducer abc(Supplier<Evaluable<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.ABC);
	}

	default VectorEvaluable def(Evaluable<TriangleData> t) {
		return new DefaultVectorEvaluable(def(() -> t));
	}

	default VectorProducer def(Supplier<Evaluable<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.DEF);
	}

	default VectorEvaluable jkl(Evaluable<TriangleData> t) {
		return new DefaultVectorEvaluable(jkl(() -> t));
	}

	default VectorProducer jkl(Supplier<Evaluable<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.JKL);
	}

	default VectorEvaluable normal(Evaluable<TriangleData> t) {
		return new DefaultVectorEvaluable(normal(() -> t));
	}

	default VectorProducer normal(Supplier<Evaluable<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.NORMAL);
	}

	default TriangleDataProducer triangle(Supplier<Evaluable<? extends TrianglePointData>> points) {
		return triangle(new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P1),
				new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P2),
				new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P3));
	}

	default TriangleDataProducer triangle(Supplier<Evaluable<? extends Vector>> p1, Supplier<Evaluable<? extends Vector>> p2, Supplier<Evaluable<? extends Vector>> p3) {
		return new TriangleDataFromVectors(ops().subtract(p2, p1), ops().subtract(p3, p1), p1);
	}
}
