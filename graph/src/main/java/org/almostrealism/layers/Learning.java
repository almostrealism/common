package org.almostrealism.layers;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

public interface Learning {

	void setLearningRate(Producer<PackedCollection<?>> learningRate);
}
