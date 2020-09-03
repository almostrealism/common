package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.util.Producer;

public interface TriangleDataProducer extends Producer<TriangleData> {
	default VectorFromTriangleData abc() { return abc(this); }

	static VectorFromTriangleData abc(Producer<TriangleData> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.ABC);
	}

	default VectorFromTriangleData def() { return def(this); }

	static VectorFromTriangleData def(Producer<TriangleData> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.DEF);
	}

	default VectorFromTriangleData jkl() { return jkl(this); }

	static VectorFromTriangleData jkl(Producer<TriangleData> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.JKL);
	}

	default VectorFromTriangleData normal() { return normal(this); }

	static VectorFromTriangleData normal(Producer<TriangleData> t) {
		return new VectorFromTriangleData(t, VectorFromTriangleData.NORMAL);
	}

	static TriangleDataFromVectors of(Producer<Vector> p1, Producer<Vector> p2, Producer<Vector> p3) {
		return new TriangleDataFromVectors(VectorProducer.subtract(p2, p1),
											VectorProducer.subtract(p3, p1),
											p1);
	}
}
