/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.ModelTestFeatures;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class GradientDescentTests implements ModelTestFeatures {
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

		CellularLayer dense = dense(1, 1).apply(shape(1));

		SequentialBlock block = new SequentialBlock(shape(1));
		block.add(dense);

		Model model = new Model(shape(1), 1e-5);
		model.add(block);

		int epochs = 300;
		int steps = 100;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(1)))
				.map(input -> input.fill(pos -> 2.0 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, func1.apply(input)))
				.collect(Collectors.toList()));

		train("linear1", model, data, epochs, steps, 0.25, 0.1);
	}

	@Test
	public void linear2() throws FileNotFoundException {
		if (testDepth < 1) return;

		CellularLayer dense = dense(2, 1).apply(shape(2));

		SequentialBlock block = new SequentialBlock(shape(2));
		block.add(dense);

		Model model = new Model(shape(2));
		model.add(block);

		int epochs = 300;
		int steps = 320;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(2)))
				.map(input -> input.fill(pos -> 5 + 2 * Math.random()))
				.map(input -> ValueTarget.of(input, func2.apply(input)))
				.collect(Collectors.toList()));

		train("linear2", model, data, epochs, steps, 1.0, 0.2);
	}

	@Test
	public void linear3() throws FileNotFoundException {
		SequentialBlock block = new SequentialBlock(shape(3));
		block.add(dense(3, 3));

		Model model = new Model(shape(3), 1e-5);
		model.add(block);

		int epochs = 300;
		int steps = 260;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(3)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, func3x3.apply(input)))
				.collect(Collectors.toList()));

		train("linear3", model, data, epochs, steps, 1.75, 0.775);
	}

	@Test
	public void linear4() throws FileNotFoundException {
		if (testDepth < 2) return;

		SequentialBlock block = new SequentialBlock(shape(3));
		block.add(dense(3, 1));
		block.add(dense(1, 1));

		Model model = new Model(shape(3), 1e-5);
		model.add(block);

		int epochs = 300;
		int steps = 260;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(3)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, func3.apply(input)))
				.collect(Collectors.toList()));

		train("linear4", model, data, epochs, steps, 0.8, 0.4);
	}

	@Test
	public void linear5() throws FileNotFoundException {
		if (testDepth < 2) return;

		try {
			initKernelMetrics();

			int inChannels = 3;
			int hiddenDim = 10;
			int outLen = 1;

			SequentialBlock block = new SequentialBlock(shape(inChannels));
			block.add(dense(inChannels, hiddenDim));
			block.add(dense(hiddenDim, outLen));

			Model model = new Model(shape(inChannels));
			model.add(block);

			int epochs = 600;
			int steps = 125;

			Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
					.mapToObj(i -> new PackedCollection<>(shape(inChannels)))
					.map(input -> input.fill(pos -> 5 + 4 * Math.random()))
					.map(input -> ValueTarget.of(input, func3.apply(input)))
					.collect(Collectors.toList()));

			train("linear5", model, data, epochs, steps, 1.0, 0.33);
		} finally {
			logKernelMetrics();
		}
	}
}
