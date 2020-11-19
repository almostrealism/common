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

	default VectorSupplier abc(Supplier<Producer<? extends VectorBank>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.ABC);
	}

	default VectorProducer def(Producer<TriangleData> t) {
		return new DefaultVectorProducer(def(() -> t));
	}

	default VectorSupplier def(Supplier<Producer<? extends VectorBank>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.DEF);
	}

	default VectorProducer jkl(Producer<TriangleData> t) {
		return new DefaultVectorProducer(jkl(() -> t));
	}

	default VectorSupplier jkl(Supplier<Producer<? extends VectorBank>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.JKL);
	}

	default VectorProducer normal(Producer<TriangleData> t) {
		return new DefaultVectorProducer(normal(() -> t));
	}

	default VectorSupplier normal(Supplier<Producer<? extends VectorBank>> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.NORMAL);
	}

	default Supplier<Producer<? extends TriangleData>> triangle(Supplier<Producer<? extends VectorBank>> points) {
		return triangle(new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P1),
				new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P2),
				new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P3));
	}

	default Supplier<Producer<? extends TriangleData>> triangle(Supplier<Producer<? extends Vector>> p1, Supplier<Producer<? extends Vector>> p2, Supplier<Producer<? extends Vector>> p3) {
		return new TriangleDataFromVectors(ops().subtract(p2, p1), ops().subtract(p3, p1), p1);
	}
}
