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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.io.CSVReceptor;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.junit.Assert;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.function.UnaryOperator;

public class GradientDescentTests implements CodeFeatures {
	private static double coeff[] = { 0.24, -0.1, 0.36 };

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
		CellularLayer dense = dense(1, 1);

		SequentialBlock block = new SequentialBlock(shape(1));
		block.add(dense);

		Model model = new Model(shape(1), 0.1);
		model.addBlock(block);

		Evaluable<PackedCollection<?>> dloss = c(2).multiply(x().subtract(y())).get();
		Evaluable<PackedCollection<?>> loss = x().subtract(y()).pow(2.0).get();

		CompiledModel runner = model.compile();

		int epochs = 70;
		int steps = 3;

		double ls = -1.0;

		try (CSVReceptor<PackedCollection<?>> receptor = new CSVReceptor<>(new FileOutputStream("results/linear1.csv"), steps)) {
			for (int i = 0; i < epochs * steps; i++) {
				PackedCollection<?> input = new PackedCollection<>(shape(1));
				input.fill(pos -> 2.0);

				PackedCollection<?> valid = func1.apply(input);
				PackedCollection<?> out = runner.forward(input);
				PackedCollection<?> grad = dloss.evaluate(out, valid);
				PackedCollection<?> l = loss.evaluate(out, valid);

				if (i % steps == 0) {
					ls = l.toDouble(0);
					System.out.println("Loss = " + ls);
				}

				receptor.push(p(l)).get().run();
				runner.backward(grad);
			}
		}


		Assert.assertTrue(ls >= 0.0);
		Assert.assertTrue(ls < 0.01);
	}

	@Test
	public void linear2() throws FileNotFoundException {
		CellularLayer dense = dense(2, 1);

		SequentialBlock block = new SequentialBlock(shape(2));
		block.add(dense);

		Model model = new Model(shape(2), 1e-3);
		model.addBlock(block);

		Evaluable<PackedCollection<?>> dloss = c(2).multiply(x().subtract(y())).get();
		Evaluable<PackedCollection<?>> loss = x().subtract(y()).pow(2.0).get();

		CompiledModel runner = model.compile();

		int epochs = 200;
		int steps = 100;

		double updatedLoss = -1.0;

		try (CSVReceptor<PackedCollection<?>> receptor = new CSVReceptor<>(new FileOutputStream("results/linear2.csv"), steps)) {
			for (int i = 0; i < epochs * steps; i++) {
				PackedCollection<?> input = new PackedCollection<>(shape(2));
				input.fill(pos -> (0.5 + 0.5 * Math.random()));

				PackedCollection<?> valid = func2.apply(input);
				PackedCollection<?> out = runner.forward(input);
				PackedCollection<?> grad = dloss.evaluate(out, valid);
				PackedCollection<?> l = loss.evaluate(out, valid);

				if (i % steps == 0) {
					System.out.println("Loss = " + l.toDouble(0));
				}

				receptor.push(p(l)).get().run();
				runner.backward(grad);

				if (i % steps == 0) {
					out = runner.forward(input);
					l = loss.evaluate(out, valid);
					updatedLoss = l.toDouble(0);
					System.out.println("\tUpdated Loss = " + updatedLoss);
				}
			}
		}

		Assert.assertTrue(updatedLoss > 0.0);
		Assert.assertTrue(updatedLoss < 0.1);
	}

	@Test
	public void linear3() throws FileNotFoundException {
		CellularLayer dense = dense(3, 3);

		SequentialBlock block = new SequentialBlock(shape(3));
		block.add(dense);

		Model model = new Model(shape(3), 1e-3);
		model.addBlock(block);

		Evaluable<PackedCollection<?>> dloss = c(2).repeat(3).all().multiply(x(3).subtract(y(3))).get();
		Evaluable<PackedCollection<?>> loss = x(3).subtract(y(3)).pow(2.0).get();

		CompiledModel runner = model.compile();

		int epochs = 200;
		int steps = 60;

		double originalLoss = -1.0;
		double updatedLoss = -1.0;

		try (CSVReceptor<PackedCollection<?>> receptor = new CSVReceptor<>(new FileOutputStream("results/linear3.csv"), steps)) {
			for (int i = 0; i < epochs * steps; i++) {
				PackedCollection<?> input = new PackedCollection<>(shape(3));
				input.fill(pos -> (0.5 + 0.5 * Math.random()));

				PackedCollection<?> valid = func3x3.apply(input);
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
		Assert.assertTrue(updatedLoss < 0.1);
	}

	@Test
	public void linear4() throws FileNotFoundException {
		SequentialBlock block = new SequentialBlock(shape(3));
		block.add(dense(3, 1));
		block.add(dense(1, 1));

		Model model = new Model(shape(3), 1e-3);
		model.addBlock(block);

		Evaluable<PackedCollection<?>> dloss = c(2).multiply(x().subtract(y())).get();
		Evaluable<PackedCollection<?>> loss = x().subtract(y()).pow(2.0).get();

		CompiledModel runner = model.compile();

		int epochs = 120;
		int steps = 100;

		double originalLoss = -1.0;
		double updatedLoss = -1.0;

		try (CSVReceptor<PackedCollection<?>> receptor = new CSVReceptor<>(new FileOutputStream("results/linear4.csv"), steps)) {
			for (int i = 0; i < epochs * steps; i++) {
				PackedCollection<?> input = new PackedCollection<>(shape(3));
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
					Assert.assertTrue((originalLoss - updatedLoss) >= 0.0);
				}
			}
		}

		Assert.assertTrue(updatedLoss > 0.0);
		Assert.assertTrue(updatedLoss < 0.01);
	}

	@Test
	public void linear5() throws FileNotFoundException {
		int inChannels = 3;
		int hiddenDim = 10;
		int outLen = 1;

		CellularLayer dense = dense(inChannels, hiddenDim);

		SequentialBlock block = new SequentialBlock(shape(inChannels));
		block.add(dense);
		block.add(dense(hiddenDim, outLen));

		Model model = new Model(shape(inChannels), 1e-3);
		model.addBlock(block);

		Evaluable<PackedCollection<?>> dloss = c(2).multiply(x().subtract(y())).get();
		Evaluable<PackedCollection<?>> loss = x().subtract(y()).pow(2.0).get();

		CompiledModel runner = model.compile();

		int epochs = 250;
		int steps = 20;

		double originalLoss = -1.0;
		double updatedLoss = -1.0;

		try (CSVReceptor<PackedCollection<?>> receptor = new CSVReceptor<>(new FileOutputStream("results/linear5.csv"), steps)) {
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

	@Test
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
