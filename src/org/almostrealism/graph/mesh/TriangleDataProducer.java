package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.VectorBank;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.computations.DefaultVectorProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.hardware.KernelizedProducer;
import static org.almostrealism.util.Ops.*;
import org.almostrealism.util.Producer;

import java.util.function.Supplier;

public interface TriangleDataProducer extends KernelizedProducer<TriangleData>, TriangleDataFeatures {
	default VectorProducer abc() { return abc(this); }

	default VectorProducer def() { return def(this); }

	default VectorProducer jkl() { return jkl(this); }

	default VectorProducer normal() { return normal(this); }
}
