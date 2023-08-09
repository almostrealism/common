package org.almostrealism.algebra;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

public interface MatrixFeatures extends CollectionFeatures {
	default <T extends PackedCollection<?>> CollectionProducer<T> matmul(Producer<T> matrix, Producer<T> vector) {
		TraversalPolicy shape = shape(matrix);
		if (shape.getDimensions() != 2)
			throw new IllegalArgumentException();

		int d = shape.length(0);
		return multiply(traverseEach(matrix), traverseEach(repeat(d, vector))).traverse(1).sum();
	}
}
