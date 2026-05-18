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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DenseLayerTests extends TestSuiteBase implements ModelTestFeatures {
	private final double[] coeff = {0.24, -0.1, 0.36};

	public PackedCollection func3x3(PackedCollection input) {
		TraversalPolicy shape = padDimensions(input.getShape(), 2);
		input = input.reshape(shape);

		PackedCollection result = new PackedCollection(shape);

		for (int n = 0; n < shape.length(0); n++) {
			result.range(shape(3), n * 3).setMem(
					coeff[0] * input.valueAt(n, 0),
					coeff[1] * input.valueAt(n, 1),
					coeff[2] * input.valueAt(n, 2));
		}

		return result;
	}

	@Test(timeout = 120000)
	public void denseBatch() throws FileNotFoundException {
		int bs = 10;

		SequentialBlock block = new SequentialBlock(shape(bs, 3));
		block.add(dense(3, 3));

		Model model = new Model(shape(bs, 3), 1e-5);
		model.add(block);

		int epochs = 300;
		int steps = 260;

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(bs, 3)))
				.map(input -> input.fill(pos -> 4 + 3 * Math.random()))
				.map(input -> ValueTarget.of(input, func3x3(input)))
				.collect(Collectors.toList()));

		train("denseBatch", model, data, epochs, steps, 0.6, 0.2);
	}
}
