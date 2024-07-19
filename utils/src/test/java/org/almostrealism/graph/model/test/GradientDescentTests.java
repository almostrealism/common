/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.graph.model.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.io.CSVReceptor;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class GradientDescentTests implements TestFeatures {
	private double coeff[] = { 0.24, -0.1, 0.36 };

	private UnaryOperator<PackedCollection<?>> func1 =
			in -> PackedCollection.of(1.5 * in.valueAt(0));
	private UnaryOperator<PackedCollection<?>> func2 =
			in -> PackedCollection.of(in.valueAt(0) * 2 + in.valueAt(1));
	private UnaryOperator<PackedCollection<?>> func3 =
			in -> PackedCollection.of(coeff[0] * in.valueAt(0) + coeff[1] * in.valueAt(1) + coeff[2] * in.valueAt(2));
	private UnaryOperator<PackedCollection<?>> func3x3 =
			in -> PackedCollection.of(coeff[0] * in.valueAt(0), coeff[1] * in.valueAt(1), coeff[2] * in.valueAt(2));

	@Test
	public void linear1() throws FileNotFoundException {
		if (testDepth < 1) return;

		CellularLayer dense = dense(1, 1);

		SequentialBlock block = new SequentialBlock(shape(1));
		block.add(dense);

		Model model = new Model(shape(1), 1e-5);
		model.addBlock(block);

		int epochs = 300;
		int steps = 100;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(1)))
				.map(input -> input.fill(pos -> 2.0 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, func1.apply(input)))
				.collect(Collectors.toList()));

		optimize("linear1", model, data, epochs, steps, 0.25, 0.1);
	}

	@Test
	public void linear2() throws FileNotFoundException {
		if (testDepth < 1) return;

		CellularLayer dense = dense(2, 1);

		SequentialBlock block = new SequentialBlock(shape(2));
		block.add(dense);

		Model model = new Model(shape(2));
		model.addBlock(block);

		int epochs = 300;
		int steps = 320;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(2)))
				.map(input -> input.fill(pos -> 5 + 2 * Math.random()))
				.map(input -> ValueTarget.of(input, func2.apply(input)))
				.collect(Collectors.toList()));

		optimize("linear2", model, data, epochs, steps, 1.0, 0.2);
	}

	@Test
	public void linear3() throws FileNotFoundException {
		CellularLayer dense = dense(3, 3);

		SequentialBlock block = new SequentialBlock(shape(3));
		block.add(dense);

		Model model = new Model(shape(3), 1e-5);
		model.addBlock(block);

		int epochs = 300;
		int steps = 260;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(3)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, func3x3.apply(input)))
				.collect(Collectors.toList()));

		optimize("linear3", model, data, epochs, steps, 1.25, 0.5);
	}

	@Test
	public void linear4() throws FileNotFoundException {
		if (testDepth < 2) return;

		SequentialBlock block = new SequentialBlock(shape(3));
		block.add(dense(3, 1));
		block.add(dense(1, 1));

		Model model = new Model(shape(3), 1e-5);
		model.addBlock(block);

		int epochs = 300;
		int steps = 260;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(3)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, func3.apply(input)))
				.collect(Collectors.toList()));

		optimize("linear4", model, data, epochs, steps, 0.8, 0.25);
	}

	@Test
	public void linear5() throws FileNotFoundException {
		if (testDepth < 2) return;

		try {
			initKernelMetrics();

			int inChannels = 3;
			int hiddenDim = 10;
			int outLen = 1;

			CellularLayer dense = dense(inChannels, hiddenDim);

			SequentialBlock block = new SequentialBlock(shape(inChannels));
			block.add(dense);
			block.add(dense(hiddenDim, outLen));

			Model model = new Model(shape(inChannels));
			model.addBlock(block);

			int epochs = 600;
			int steps = 125;

			Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
					.mapToObj(i -> new PackedCollection<>(shape(inChannels)))
					.map(input -> input.fill(pos -> 5 + 4 * Math.random()))
					.map(input -> ValueTarget.of(input, func3.apply(input)))
					.collect(Collectors.toList()));

			optimize("linear5", model, data, epochs, steps, 1.0, 0.25);
		} finally {
			logKernelMetrics();
		}
	}

	public void optimize(String name, Model model, Supplier<Dataset<?>> data, int epochs, int steps,
						 double lossTarget, double minLoss) throws FileNotFoundException {
		i: for (int i = 0; i < 6; i++) {
			ModelOptimizer optimizer = new ModelOptimizer(model.compile(), data);

			try (CSVReceptor<Double> receptor = new CSVReceptor<>(new FileOutputStream("results/" + name + ".csv"), steps)) {
				optimizer.setReceptor(receptor);
				optimizer.setLogFrequency(2);

				optimizer.setLossTarget(lossTarget);
				optimizer.optimize(epochs);
				log("Completed " + optimizer.getTotalIterations() + " epochs");

				if (optimizer.getTotalIterations() < 5) {
					optimizer.setLossTarget(minLoss);
					optimizer.optimize(epochs);
					log("Completed " + optimizer.getTotalIterations() + " epochs");
				}

				if (optimizer.getTotalIterations() < 5) {
					continue i;
				}

				Assert.assertTrue(optimizer.getLoss() > 0.0);
				Assert.assertTrue(optimizer.getLoss() < optimizer.getLossTarget());
				return;
			}
		}

		Assert.fail();
	}

	// @Test
	public void embeddings() throws FileNotFoundException {
		int inChannels = 3;
		int timeLen = 10;
		int outLen = 1;

		CellularLayer dense = dense(inChannels, timeLen);

		SequentialBlock block = new SequentialBlock(shape(inChannels));
		block.add(dense);
		block.add(silu(shape(timeLen)));
		block.add(dense(timeLen, outLen));

		Model model = new Model(shape(inChannels), 1e-3);
		model.addBlock(block);

		Evaluable<PackedCollection<?>> dloss = c(2).multiply(x().subtract(y())).get();
		Evaluable<PackedCollection<?>> loss = x().subtract(y()).pow(2.0).get();

		CompiledModel runner = model.compile();

		int epochs = 600;
		int steps = 10;

		double originalLoss = -1.0;
		double updatedLoss = -1.0;

		try (CSVReceptor<PackedCollection<?>> receptor = new CSVReceptor<>(new FileOutputStream("results/embeddings.csv"), steps)) {
			for (int i = 0; i < epochs * steps; i++) {
				PackedCollection<?> input = new PackedCollection<>(shape(inChannels));
				input.fill(pos -> 0.5 + 2 * Math.random());

				PackedCollection<?> valid = func3.apply(input);
				PackedCollection<?> out = runner.forward(input);
				PackedCollection<?> grad = dloss.evaluate(out, valid);
				PackedCollection<?> l = loss.evaluate(out, valid);

				if (i % steps == 0) {
					originalLoss = l.toDouble(0);
					System.out.println("Loss = " + originalLoss);
				}

				receptor.push(p(l)).get().run();
				runner.backward(grad);

				if (i % steps == 0) {
					out = runner.forward(input);
					l = loss.evaluate(out, valid);
					updatedLoss = l.toDouble(0);
					System.out.println("\tUpdated Loss = " + updatedLoss);
					System.out.println("\tChange = " + (originalLoss - updatedLoss));
					Assert.assertTrue((originalLoss - updatedLoss) > 0.0);
				}
			}
		}

		Assert.assertTrue(updatedLoss > 0.0);
		Assert.assertTrue(updatedLoss < 0.01);
	}
}
