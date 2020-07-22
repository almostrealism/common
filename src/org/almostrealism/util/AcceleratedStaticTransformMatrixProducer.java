package org.almostrealism.util;

import org.almostrealism.algebra.TransformMatrix;

public class AcceleratedStaticTransformMatrixProducer extends AcceleratedStaticProducer<TransformMatrix> {
	public AcceleratedStaticTransformMatrixProducer(TransformMatrix value, Producer<TransformMatrix> output) {
		super(value, output);
	}
}
