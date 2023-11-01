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
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.io.CSVReceptor;
import io.almostrealism.relation.Factor;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.GradientPropagation;
import org.almostrealism.layers.Propagation;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class GradientDescentTests implements CodeFeatures {
	private boolean enableCellular = false;

	private static double coeff[] = { 0.24, -0.1, 0.36 };

	private UnaryOperator<PackedCollection<?>> func1 =
			in -> PackedCollection.of(1.5 * in.valueAt(0));
	private UnaryOperator<PackedCollection<?>> func2 =
			in -> PackedCollection.of(in.valueAt(0) * 2 + in.valueAt(1) * in.valueAt(1) + 3);
	private UnaryOperator<PackedCollection<?>> func3 =
			in -> PackedCollection.of(coeff[0] * in.valueAt(0) + coeff[1] * in.valueAt(1) + coeff[2] * in.valueAt(2));

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
		Factor<PackedCollection<?>> operator = input -> c(input).multiply(c(p(weights))).sum();

		Propagation backwards = new GradientPropagation(operator, p(weights), p(biases));

		Supplier<Runnable> init = a(p(weights.each()), divide(randn(shape(size, nodes)).each(), c(size).all()));

		return layer("dense", shape(size), outputShape,
					Cell.of(operator), backwards, List.of(weights, biases), init);
	}

	@Test
	public void linear1() throws FileNotFoundException {
		CellularLayer dense = dense(1, 1);

		SequentialBlock block = new SequentialBlock(shape(1));
		block.add(dense);

		Model model = new Model(shape(1), 0.1);
		model.addBlock(block);

		Evaluable<PackedCollection<?>> dloss = c(2).multiply(x().subtract(y())).get();
		Evaluable<PackedCollection<?>> loss = x().subtract(y()).pow(2.0).get();

		CompiledModel runner = model.compile();

		int epochs = 1000;
		int steps = 100;

		try (CSVReceptor<PackedCollection<?>> receptor = new CSVReceptor<>(new FileOutputStream("results/linear1.csv"), steps)) {
			for (int i = 0; i < epochs * steps; i++) {
				PackedCollection<?> input = new PackedCollection<>(shape(1));
				// input.fill(pos -> Math.random());
				input.fill(pos -> 2.0);

				PackedCollection<?> valid = func1.apply(input);
				PackedCollection<?> out = runner.forward(input);
				PackedCollection<?> grad = dloss.evaluate(out, valid);
				PackedCollection<?> l = loss.evaluate(out, valid);

				if (i % steps == 0) {
					System.out.println("Loss = " + l.toDouble(0));
				}

				receptor.push(p(l)).get().run();
				runner.backward(grad);
			}
		}
	}

	@Test
	public void embeddings() throws FileNotFoundException {
		int inChannels = 10;
		int timeLen = 1;
		int outLen = 1;

		CellularLayer dense = dense(inChannels, timeLen);

		SequentialBlock block = new SequentialBlock(shape(inChannels));
		block.add(dense);
//		block.add(silu(shape(timeLen)));
//		block.add(dense(timeLen, outLen));

		Model model = new Model(shape(inChannels), 0.01);
		model.addBlock(block);

		Evaluable<PackedCollection<?>> dloss = c(2).multiply(x().subtract(y())).get();
		Evaluable<PackedCollection<?>> loss = x().subtract(y()).pow(2.0).get();

		CompiledModel runner = model.compile();

		int epochs = 100;
		int steps = 1000;

		try (CSVReceptor<PackedCollection<?>> receptor = new CSVReceptor<>(new FileOutputStream("results/training_loss.csv"), steps)) {
			for (int i = 0; i < epochs * steps; i++) {
				PackedCollection<?> input = new PackedCollection<>(shape(inChannels));
				input.fill(pos -> Math.random());

				PackedCollection<?> valid = func3.apply(input);
				PackedCollection<?> out = runner.forward(input);
				PackedCollection<?> grad = dloss.evaluate(out, valid);
				PackedCollection<?> l = loss.evaluate(out, valid);

				if (i % steps == 0) {
					System.out.println("Loss = " + l.toDouble(0));
				}

				receptor.push(p(l)).get().run();
				runner.backward(grad);
			}
		}
	}
}
