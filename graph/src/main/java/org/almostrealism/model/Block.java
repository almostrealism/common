/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.model;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.Input;
import org.almostrealism.layers.Component;
import org.almostrealism.layers.Layer;
import org.almostrealism.layers.LayerFeatures;

import java.util.function.Function;
import java.util.function.Supplier;

public interface Block extends Component, CellularPropagation<PackedCollection<?>>, Setup, LayerFeatures {
	boolean enableAlignCountReshape = false;

	TraversalPolicy getInputShape();

	default Runnable forward(PackedCollection<?> input) {
		return getForward().push(CollectionFeatures.getInstance().cp(input)).get();
	}

	default Supplier<Runnable> forward(Producer<PackedCollection<?>> input) {
		return getForward().push(input);
	}

	default Block reshape(int... dims) {
		return reshape(new TraversalPolicy(dims));
	}

	default Block reshape(TraversalPolicy shape) {
		if (getOutputShape().getTotalSize() != shape.getTotalSize()) {
			throw new IllegalArgumentException("Cannot reshape " + getOutputShape() + " to " + shape);
		}

		if (enableAlignCountReshape) {
			shape = shape.alignCount(getOutputShape());
		}

		return andThen(reshape(getOutputShape(), shape));
	}

	default Block scale(double factor) {
		return andThen(scale(getOutputShape(), factor));
	}

	default Block enumerate(int depth, int axis, int len, ComputeRequirement... requirements) {
		// TODO  There should be a much more direct way to determine the resulting shape than this
		TraversalPolicy resultShape =
				traverse(depth, c(Input.value(getOutputShape(), 0)))
					.enumerate(axis, len)
						.getShape();
		return andThen(layer("enumerate", getOutputShape(), resultShape,
				in -> traverse(depth, in).enumerate(axis, len),
				requirements));
	}

	default Block enumerate(TraversalPolicy shape, ComputeRequirement... requirements) {
		if (getOutputShape().getTotalSize() % shape.getTotalSize() != 0) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy resultShape = shape
				.prependDimension(getOutputShape().getTotalSize() / shape.getTotalSize());
		return andThen(layer("enumerate", getOutputShape(), resultShape,
				in -> CollectionFeatures.getInstance().enumerate(shape, in),
				requirements));
	}

	default Block andThenDense(PackedCollection<?> weights, ComputeRequirement... requirements) {
		return andThen(dense(weights, requirements));
	}

	default Block andThenDense(PackedCollection<?> weights,
							   PackedCollection<?> biases,
							   ComputeRequirement... requirements) {
		return andThen(dense(weights, biases, requirements));
	}

	default SequentialBlock branch() {
		BranchBlock split = new BranchBlock(getOutputShape());
		SequentialBlock branch = split.append(new SequentialBlock(getOutputShape()));
		andThen(split);
		return branch;
	}

	default <T extends Block> Block andThen(T next) {
//		SequentialBlock block = new SequentialBlock(getInputShape());
//		block.add(this);
//		block.add(next);
//		return block;

		getForward().setReceptor(next.getForward());
		next.getBackward().setReceptor(getBackward());
		return next;
	}

	default <T extends Block> Block andThen(Function<TraversalPolicy, T> next) {
		return andThen(next.apply(getOutputShape()));
	}

	default <T extends Receptor<PackedCollection<?>>> T andThen(T next) {
		if (Layer.propagationWarnings)
			warn("andThen(" + next + ") may not support backpropagation");
		getForward().setReceptor(next);
		return next;
	}

	default CollectionReceptor andThen(PackedCollection<?> destination) {
		if (Layer.propagationWarnings)
			warn("andThen(" + destination + ") may not support backpropagation");
		CollectionReceptor r = new CollectionReceptor(destination);
		getForward().setReceptor(r);
		return r;
	}

	@Override
	default String describe() {
		return getInputShape().toStringDetail() + " -> " + getOutputShape().toStringDetail();
	}
}
