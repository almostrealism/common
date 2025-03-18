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

public class DenseLayerTests implements ModelTestFeatures {
	private double coeff[] = { 0.24, -0.1, 0.36 };

	private UnaryOperator<PackedCollection<?>> func3x3 =
			in -> pack(
					coeff[0] * in.valueAt(0),
					coeff[1] * in.valueAt(1),
					coeff[2] * in.valueAt(2));

	@Test
	public void denseBatch() throws FileNotFoundException {
		int bs = 10;

		SequentialBlock block = new SequentialBlock(shape(bs, 3));
		block.add(dense(3, 3));

		Model model = new Model(shape(bs, 3), 1e-5);
		model.add(block);

		int epochs = 300;
		int steps = 260;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(3)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, func3x3.apply(input)))
				.collect(Collectors.toList()));

		train("denseBatch", model, data, epochs, steps, 1.75, 0.775);
	}
}
