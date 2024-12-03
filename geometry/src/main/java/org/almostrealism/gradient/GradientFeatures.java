package org.almostrealism.gradient;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

public interface GradientFeatures extends CollectionFeatures {
	boolean enableMultiplyEach = true;

	default <T extends PackedCollection<?>> CollectionProducer<PackedCollection<?>> combineGradient(
								CollectionProducer<T> func,
								Producer<T> input, Producer<T> gradient) {
		int inSize = shape(input).getTotalSize();
		int outSize = shape(gradient).getTotalSize();
		return multiplyGradient(func.delta(input).reshape(outSize, inSize)
				.traverse(1), gradient, inSize)
				.traverse(0)
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(inSize))
				.each();
	}

	default <T extends PackedCollection<?>> CollectionProducer<PackedCollection<?>> multiplyGradient(
			CollectionProducer<T> p, Producer<T> gradient, int inSize) {
		int outSize = shape(gradient).getTotalSize();

		if (enableMultiplyEach) {
			return p.multiply(c(gradient).reshape(outSize).traverse(1).repeat(inSize));
		} else {
			return p.multiply(c(gradient).reshape(outSize).traverse(1).repeat(inSize).traverse(1));
		}
	}
}
