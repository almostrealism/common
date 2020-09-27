package org.almostrealism.util;

import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.TransformMatrixBank;
import org.almostrealism.hardware.MemoryBank;

public class AcceleratedStaticTransformMatrixProducer extends AcceleratedStaticProducer<TransformMatrix> {
	public AcceleratedStaticTransformMatrixProducer(TransformMatrix value, Producer<TransformMatrix> output) {
		super(value, output);
	}

	@Override
	public MemoryBank<TransformMatrix> createKernelDestination(int size) { return new TransformMatrixBank(size); }
}
