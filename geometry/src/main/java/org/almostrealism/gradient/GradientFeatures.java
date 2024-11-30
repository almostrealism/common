package org.almostrealism.gradient;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

public interface GradientFeatures extends CollectionFeatures {
	default <T extends PackedCollection<?>> CollectionProducer<PackedCollection<?>> combineGradient(
								CollectionProducer<T> func,
								Producer<T> input,
								Producer<T> gradient) {
		int inSize = shape(input).getTotalSize();
		int outSize = shape(gradient).getTotalSize();
		return func.delta(input).reshape(outSize, inSize)
				.traverse(1)
				.multiply(c(gradient).reshape(outSize).traverse(1).repeat(inSize))
				.traverse(0)
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(inSize))
				.each();
	}
}
