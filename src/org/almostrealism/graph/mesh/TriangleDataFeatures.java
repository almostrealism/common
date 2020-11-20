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
import org.almostrealism.algebra.VectorBank;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.VectorSupplier;
import org.almostrealism.algebra.computations.DefaultVectorProducer;
import org.almostrealism.util.Producer;

import java.util.function.Supplier;

import static org.almostrealism.util.Ops.ops;

public interface TriangleDataFeatures extends VectorFeatures {
	default VectorProducer abc(Producer<TriangleData> t) {
		return new DefaultVectorProducer(abc(() -> t));
	}

	default VectorSupplier abc(Supplier<Producer<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.ABC);
	}

	default VectorProducer def(Producer<TriangleData> t) {
		return new DefaultVectorProducer(def(() -> t));
	}

	default VectorSupplier def(Supplier<Producer<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.DEF);
	}

	default VectorProducer jkl(Producer<TriangleData> t) {
		return new DefaultVectorProducer(jkl(() -> t));
	}

	default VectorSupplier jkl(Supplier<Producer<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.JKL);
	}

	default VectorProducer normal(Producer<TriangleData> t) {
		return new DefaultVectorProducer(normal(() -> t));
	}

	default VectorSupplier normal(Supplier<Producer<? extends TriangleData>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.NORMAL);
	}

	default TriangleDataSupplier triangle(Supplier<Producer<? extends TrianglePointData>> points) {
		return triangle(new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P1),
				new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P2),
				new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P3));
	}

	default TriangleDataSupplier triangle(Supplier<Producer<? extends Vector>> p1, Supplier<Producer<? extends Vector>> p2, Supplier<Producer<? extends Vector>> p3) {
		return new TriangleDataFromVectors(ops().subtract(p2, p1), ops().subtract(p3, p1), p1);
	}
}
