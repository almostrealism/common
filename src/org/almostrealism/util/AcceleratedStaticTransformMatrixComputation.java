package org.almostrealism.util;

import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.TransformMatrixBank;
import org.almostrealism.hardware.MemoryBank;

public class AcceleratedStaticTransformMatrixComputation extends AcceleratedStaticComputation<TransformMatrix> {
	public AcceleratedStaticTransformMatrixComputation(TransformMatrix value, Producer<TransformMatrix> output) {
		super(value, output);
	}
}
