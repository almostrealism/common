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

public interface TrianglePointDataFeatures extends VectorFeatures {
	default VectorEvaluable p1(Evaluable<TrianglePointData> t) {
		return new DefaultVectorEvaluable(p1(() -> t));
	}

	default VectorProducer p1(Supplier<Evaluable<? extends TrianglePointData>> t) {
		return new VectorFromTrianglePointData(t, VectorFromTrianglePointData.P1);
	}

	default VectorEvaluable p2(Evaluable<TrianglePointData> t) {
		return new DefaultVectorEvaluable(p2(() -> t));
	}

	default VectorProducer p2(Supplier<Evaluable<? extends TrianglePointData>> t) {
		return new VectorFromTrianglePointData(t, VectorFromTrianglePointData.P2);
	}

	default VectorEvaluable p3(Evaluable<TrianglePointData> t) {
		return new DefaultVectorEvaluable(p3(() -> t));
	}

	default VectorProducer p3(Supplier<Evaluable<? extends TrianglePointData>> t) {
		return new VectorFromTrianglePointData(t, VectorFromTrianglePointData.P3);
	}

	default TrianglePointDataProducer points(Supplier<Evaluable<? extends Vector>> p1, Supplier<Evaluable<? extends Vector>> p2, Supplier<Evaluable<? extends Vector>> p3) {
		return new TrianglePointDataFromVectors(p1, p2, p3);
	}

	static TriangleDataFeatures getInstance() {
		return new TriangleDataFeatures() { };
	}
}
