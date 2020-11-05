package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.computations.DefaultVectorProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.hardware.KernelizedProducer;
import static org.almostrealism.util.Ops.*;
import org.almostrealism.util.Producer;

public interface TriangleDataProducer extends KernelizedProducer<TriangleData>, VectorFeatures {
	default VectorProducer abc() { return abc(this); }

	static VectorProducer abc(Producer<TriangleData> t) {
		return new DefaultVectorProducer(new VectorFromTriangleData(t, VectorFromTriangleData.ABC));
	}

	default VectorProducer def() { return def(this); }

	static VectorProducer def(Producer<TriangleData> t) {
		return new DefaultVectorProducer(new VectorFromTriangleData(t, VectorFromTriangleData.DEF));
	}

	default VectorProducer jkl() { return jkl(this); }

	static VectorProducer jkl(Producer<TriangleData> t) {
		return new DefaultVectorProducer(new VectorFromTriangleData(t, VectorFromTriangleData.JKL));
	}

	default VectorProducer normal() { return normal(this); }

	static VectorProducer normal(Producer<TriangleData> t) {
		return new DefaultVectorProducer(new VectorFromTriangleData(t, VectorFromTriangleData.NORMAL));
	}

	static TriangleDataProducer of(Producer<TrianglePointData> points) {
		return of(new DefaultVectorProducer(new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P1)),
				new DefaultVectorProducer(new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P2)),
				new DefaultVectorProducer(new VectorFromTrianglePointData(points, VectorFromTrianglePointData.P3)));
	}

	static TriangleDataProducer of(Producer<Vector> p1, Producer<Vector> p2, Producer<Vector> p3) {
		return new DefaultTriangleDataProducer(new TriangleDataFromVectors(ops().subtract(p2, p1),
													ops().subtract(p3, p1), p1));
	}
}
