/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.IndexProjectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

import java.util.function.Function;
import java.util.function.Supplier;

public interface TriangleFeatures extends VectorFeatures {


	default CollectionProducer<Vector> abc(Producer<PackedCollection<?>> t) {
		return vector(t, 0);
	}

	default CollectionProducer<Vector> def(Producer<PackedCollection<?>> t) {
		return vector(t, 1);
	}

	default CollectionProducer<Vector> jkl(Producer<PackedCollection<?>> t) {
		return vector(t, 2);
	}

	default CollectionProducer<Vector> normal(Producer<PackedCollection<?>> t) {
		return vector(t, 4);
	}

	default CollectionProducer<PackedCollection<Vector>> triangle(Supplier<Evaluable<? extends PackedCollection<?>>> points) {
		return triangle(
				point(points, 0),
				point(points, 1),
				point(points, 2));
	}

	default CollectionProducer<PackedCollection<Vector>> triangle(Producer<Vector> p1,
																	 Producer<Vector> p2,
																	 Producer<Vector> p3) {
		Producer<Vector> abc = subtract(p2, p1);
		Producer<Vector> def = subtract(p3, p1);
		Producer jkl = p1;
		return triangle(abc, def, jkl, normalize(crossProduct(abc, def)));
	}

	default CollectionProducer<PackedCollection<Vector>> triangle(Producer<Vector> abc, Producer<Vector> def,
																  Producer<Vector> jkl, Producer<Vector> normal) {
		return concat(shape(4, 3),
				reshape(shape(1, 3), abc),
				reshape(shape(1, 3), def),
				reshape(shape(1, 3), jkl),
				reshape(shape(1, 3), normal));
	}

	default CollectionProducerComputationBase<Vector, Vector> point(Supplier<Evaluable<? extends PackedCollection<?>>> points, int index) {
		return new DefaultTraversableExpressionComputation<>("point", shape(3),
				(Function<TraversableExpression[], CollectionExpression>) args ->
						new IndexProjectionExpression(shape(3),
							idx -> e(index * 3).add(idx.imod(3)), args[1]),
						(Producer) points)
				.setPostprocessor(Vector.postprocessor());
	}

	default CollectionProducer<PackedCollection<Vector>> points(Producer<Vector> p1,
																Producer<Vector> p2,
																Producer<Vector> p3) {
		return concat(shape(3, 3), (Producer) p1, (Producer) p2, (Producer)  p3);
	}

	static TriangleFeatures getInstance() {
		return new TriangleFeatures() { };
	}
}
