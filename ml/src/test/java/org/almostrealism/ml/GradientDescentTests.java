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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import io.almostrealism.relation.Factor;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.GradientPropagation;
import org.almostrealism.layers.Propagation;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.junit.Test;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class GradientDescentTests implements CodeFeatures {
	private boolean enableCellular = false;

	private UnaryOperator<PackedCollection<?>> func =
			in -> PackedCollection.of(in.valueAt(0) * 2 + in.valueAt(1) * in.valueAt(1) + 3);

	@Override
	public CellularLayer dense(int size, int nodes, boolean bias) {
		TraversalPolicy outputShape = shape(nodes);

		PackedCollection<?> weights = new PackedCollection<>(shape(size, nodes));
		PackedCollection<?> biases = new PackedCollection<>(shape(nodes));


//		Function<Producer<PackedCollection<?>>, CollectionProducer<PackedCollection<?>>> operator =
//				input -> c(input).repeat(nodes).traverseEach()
//						.multiply(c(p(weights))
//								.enumerate(1, 1))
//						.traverse(1).sum()
//						.add(p(biases));
		Factor<PackedCollection<?>> operator = input -> c(input).multiply(c(p(weights)));

		Propagation backwards = new GradientPropagation(operator, p(weights), p(biases));

		Supplier<Runnable> init = a(p(weights.traverseEach()), divide(randn(shape(size, nodes)).traverseEach(), c(size).traverse(0)));

		if (!enableCellular) {
			return layer("dense", shape(size), outputShape,
					Cell.of((Function) operator), backwards, List.of(weights, biases), init);
		}

		return layer("dense", shape(size), outputShape,
				Cell.of((input, next) -> {
					PackedCollection<?> output = new PackedCollection<>(outputShape);

					OperationList ops = new OperationList();
					Producer<PackedCollection<?>> dense =
							c(input).repeat(nodes).traverseEach()
									.multiply(c(p(weights))
											.enumerate(1, 1))
									.traverse(1).sum()
									.add(p(biases));

					ops.add(output.traverse(1).getShape().getSize(), dense, p(output.traverse(1)));

					if (next != null) ops.add(next.push(p(output)));
					return ops;
				}), backwards, List.of(weights, biases), init);
	}

	@Test
	public void embeddings() {
		int inChannels = 2;
		int timeLen = 1;
		int outLen = 1;

		CellularLayer dense = dense(inChannels, timeLen);

		SequentialBlock block = new SequentialBlock(shape(inChannels));
		block.add(dense);
//		block.add(silu(shape(timeLen)));
//		block.add(dense(timeLen, outLen));

		Model model = new Model(shape(inChannels));
		model.addBlock(block);

		Evaluable<PackedCollection<?>> loss = c(2).multiply(cv(shape(1), 0).subtract(cv(shape(1), 1))).get();

		model.setup().get().run();

		for (int i = 0; i < 1; i++) {
			PackedCollection<?> input = new PackedCollection<>(inChannels);
			input.fill(pos -> Math.random());

			PackedCollection<?> out = model.forward(input);
			PackedCollection<?> grad = loss.evaluate(out, func.apply(out));
			model.backward(grad);
		}
	}
}
