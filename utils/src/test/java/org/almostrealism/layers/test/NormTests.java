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

package org.almostrealism.layers.test;

import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Process;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.IOException;

public class NormTests implements LayerFeatures, TestFeatures {
	protected void validate(int groups, int groupSize, int v,
							PackedCollection<?> in, PackedCollection<?> out,
							PackedCollection<?> weights, PackedCollection<?> biases) {
		int tot = groupSize * v;

		in = in.reshape(groups, groupSize, v);
		out = out.reshape(groups, groupSize, v);
		weights = weights.reshape(groups, groupSize, v);
		biases = biases.reshape(groups, groupSize, v);

		double eps = Hardware.getLocalHardware().getPrecision().epsilon();

		for (int i = 0; i < groups; i++) {
			double sum = 0;

			for (int j = 0; j < groupSize; j++) {
				for (int k = 0; k < v; k++) {
					sum += in.valueAt(i, j, k);
				}
			}

			double mean = sum / tot;

			double variance = 0;
			for (int j = 0; j < groupSize; j++) {
				for (int k = 0; k < v; k++) {
					double d = in.valueAt(i, j, k) - mean;
					variance += d * d;
				}
			}

			variance /= tot;

			for (int j = 0; j < groupSize; j++) {
				for (int k = 0; k < v; k++) {
					double d = in.valueAt(i, j, k) - mean;
					double norm = d / Math.sqrt(variance + eps);
					double o = norm * weights.valueAt(i, j, k) + biases.valueAt(i, j, k);
					assertEquals(o, out.valueAt(i, j, k));
				}
			}
		}
	}

	@Test
	public void normLayer() {
//		int c = 20;
//		int v = 10;

		int c = 12;
		int v = 10;
		int groups = 4;

		PackedCollection<?> weights = new PackedCollection<>(c * v).randnFill();
		PackedCollection<?> biases = new PackedCollection<>(c * v).randnFill();

		PackedCollection<?> in = new PackedCollection<>(shape(c, v)).randnFill();
		PackedCollection<?> out = new PackedCollection<>(shape(c, v));

		CellularLayer layer = norm(groups, weights, biases);
		layer.andThen(out);

		Process.optimized(layer.forward(cp(in))).get().run();

		out.traverse(1).print();

		int groupSize = c / groups;
		validate(groups, groupSize, v, in, out, weights, biases);
	}

	@Test
	public void normModel() throws IOException {
		if (skipLongTests) return;

//		int c = 20;
//		int v = 10;

		int c = 12;
		int v = 10;
		int groups = 4;

		PackedCollection<?> weights = new PackedCollection<>(c * v).randnFill();
		PackedCollection<?> biases = new PackedCollection<>(c * v).randnFill();

		PackedCollection<?> in = new PackedCollection<>(shape(c, v)).randnFill();

		Model model = new Model(shape(c, v));
		model.addLayer(norm(groups, weights, biases));

		OperationProfileNode profile = initKernelMetrics(new OperationProfileNode());

		try {
			CompiledModel compiled = model.compile(profile);
			PackedCollection<?> out = compiled.forward(in);
			validate(groups, c / groups, v, in, out, weights, biases);
		} finally {
			profile.save("results/logs/normModel.xml");
		}
	}
}
