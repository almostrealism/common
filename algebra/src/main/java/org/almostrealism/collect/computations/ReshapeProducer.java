/*
 * Copyright 2023 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.KernelSupport;

public class ReshapeProducer<T extends Shape<T>> implements CollectionProducer<T>, Shape<Producer<T>>, KernelSupport {
	private TraversalPolicy shape;
	private int traversalAxis;
	private Producer<T> producer;

	public ReshapeProducer(int traversalAxis, Producer<T> producer) {
		this.traversalAxis = traversalAxis;
		this.producer = producer;
	}

	public ReshapeProducer(TraversalPolicy shape, Producer<T> producer) {
		this.shape = shape;
		this.producer = producer;
	}

	@Override
	public TraversalPolicy getShape() {
		if (shape == null) {
			TraversalPolicy inputShape = ((Shape) producer).getShape();
			return inputShape.traverse(traversalAxis);
		} else {
			return shape;
		}
	}

	@Override
	public Producer<T> reshape(TraversalPolicy shape) {
		return new ReshapeProducer<>(shape, producer);
	}

	@Override
	public Evaluable<T> get() {
		Evaluable<T> eval = producer.get();

		return args -> {
			Shape out = eval.evaluate(args);

			if (shape == null) {
				return eval.evaluate(args).reshape(out.getShape().traverse(traversalAxis));
			} else {
				return eval.evaluate(args).reshape(shape);
			}
		};
	}
}
