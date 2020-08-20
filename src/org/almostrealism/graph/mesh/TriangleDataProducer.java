package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.util.Producer;

public interface TriangleDataProducer extends Producer<TriangleData> {
	static TriangleDataFromVectors of(Producer<Vector> p1, Producer<Vector> p2, Producer<Vector> p3) {
		Producer<Vector> abc = VectorProducer.subtract(p1, p2);
		Producer<Vector> def = VectorProducer.subtract(p1, p3);
		Producer<Vector> jkl = p1;
		return new TriangleDataFromVectors(abc, def, jkl);
	}
}
