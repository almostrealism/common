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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Process;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.PropagationCell;
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
		weights = weights == null ? null : weights.reshape(groups, groupSize, v);
		biases = biases == null ? null : biases.reshape(groups, groupSize, v);

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

					double o = norm;
					if (weights != null) o = o * weights.valueAt(i, j, k);
					if (biases != null) o = o + biases.valueAt(i, j, k);

					assertEquals(o, out.valueAt(i, j, k));
				}
			}
		}
	}

	@Test
	public void normComputation() {
		double eps = Hardware.getLocalHardware().getPrecision().epsilon();

		int c = 12;
		int v = 10;
		int groups = 4;

		TraversalPolicy shape = shape(c, v);

		PackedCollection<?> o = new PackedCollection<>(shape.getTotalSize());
		o.fill(pos -> Math.random());

		kernelTest(() -> {
					CollectionProducer<?> input = cp(o).reshape(-1, groups, shape.getTotalSize() / groups);
					return input
							.subtractMean(2)
							.divide(input.variance(2).add(c(eps)).sqrt())
							.reshape(-1, shape.getTotalSize());
				},
				output -> {
					validate(groups, c / groups, v, o, output, null, null);
				}, false, false, true);
	}

	@Test
	public void normLayer() {
		int c = 12;
		int v = 10;
		int groups = 4;

		PackedCollection<?> in = new PackedCollection<>(shape(c, v)).randnFill();
		PackedCollection<?> out = new PackedCollection<>(shape(c, v));

		CellularLayer layer = norm(shape(c, v), groups, false);
		layer.andThen(out);

		Process.optimized(layer.forward(cp(in))).get().run();
		out.traverse(1).print();

		int groupSize = c / groups;
		validate(groups, groupSize, v, in, out, null, null);
	}

	@Test
	public void normLayerTrainable() {
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
	public void normDelta() {
		double eps = Hardware.getLocalHardware().getPrecision().epsilon();

		int c = 2;
		int groups = 1;

		PackedCollection<?> o = new PackedCollection<>(c).fill(1.0, 1.5);

		kernelTest(() -> {
					CollectionProducer<?> input = cp(o).reshape(-1, groups, c / groups);
					CollectionProducer out = input
							.subtractMean(2)
							.divide(input.variance(2).add(c(eps)).sqrt())
							.reshape(-1, c);
					return out.delta(input);
				},
				output -> {
					output = output.reshape(2, 2);
					output.traverse(1).print();

					int groupSize = c / groups;

					for (int g = 0; g < groups; g++) {
						int start = g * groupSize;

						PackedCollection<?> xGroup = o.range(shape(groupSize), start);

						double muG = xGroup.doubleStream().sum() / groupSize;
						double varG = xGroup.doubleStream().map(v -> Math.pow(v, 2.0)).sum() / groupSize - Math.pow(muG, 2.0);
						double stdG = Math.sqrt(varG + eps);


						for (int i = 0; i < 2; i++) {
							for (int j = 0; j < 2; j++) {
								double out = output.valueAt(start + i, start + j);
								double k0 = o.valueAt(0);
								double k1 = o.valueAt(1);

								if (i == 0 && j == 0) {
									double expected = (1 / stdG)
											- ((k0 - muG) / (stdG * stdG)) * ((k0 - k1) / (2 * stdG))
											- 1 / (2 * stdG);
									log(expected + " vs " + out);
									// assertEquals(expected, out);
								}
							}
						}
					}
				}, false, true, false);
	}

	@Test
	public void normBackwards() {
		if (skipLongTests || skipKnownIssues) return;

		int c = 120;
		int groups = 4;

		PackedCollection<?> lr = pack(0.01);
		PackedCollection<?> input = new PackedCollection(shape(c)).fill(() -> Math.random() / 10.0);
		PackedCollection<?> gradient = new PackedCollection<>(shape(c)).fill(() -> Math.random() / 4.0);

		PackedCollection<?> result = new PackedCollection<>(shape(c));

		CellularLayer layer = norm(shape(c), groups, false);
		((PropagationCell) layer.getBackward()).setLearningRate(cp(lr));
		((PropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().setReceptor(into(result));

		Process.optimized(layer.getBackward().push(p(gradient))).get().run();

		double eps = Hardware.getLocalHardware().getPrecision().epsilon();

		int groupSize = c / groups;

		for (int g = 0; g < groups; g++) {
			int start = g * groupSize;

			PackedCollection<?> xGroup = input.range(shape(groupSize), start);
			PackedCollection<?> dLdyGroup = gradient.range(shape(groupSize), start);

			double muG = xGroup.doubleStream().sum() / groupSize;
			double varG = variance(cp(xGroup)).evaluate().toDouble();
			double stdG = Math.sqrt(varG + eps);

			PackedCollection<?> xHatGroup = cp(xGroup).subtract(c(muG)).divide(c(stdG)).evaluate();

			PackedCollection<?> dLdHatXGroup = dLdyGroup;

			double dLdHatXGroupMean = dLdHatXGroup.doubleStream().sum() / groupSize;
			PackedCollection<?> dLdHatXGroupXHatGroup = cp(dLdHatXGroup).multiply(cp(xHatGroup)).evaluate();

			double dLdHatXGroupXHatGroupMean = dLdHatXGroupXHatGroup.doubleStream().sum() / groupSize;

			PackedCollection<?> dLdXGroup = cp(dLdHatXGroup)
					.subtract(c(dLdHatXGroupMean))
					.subtract(cp(xHatGroup).multiply(c(dLdHatXGroupXHatGroupMean)))
					.evaluate();
			for (int i = 0; i < groupSize; i++) {
				double expected = dLdXGroup.valueAt(i) / stdG;
				double actual = result.valueAt(start + i);

				log(expected + " vs " + actual);
				assertEquals(expected, actual);
			}
		}
	}

	@Test
	public void normBackwardsBias() {
		int c = 2;
		int groups = 1;

		PackedCollection<?> lr = pack(0.01);
		PackedCollection<?> input = new PackedCollection(shape(c)).fill(1.0, 1.01);
		PackedCollection<?> gradient = new PackedCollection<>(shape(c)).fill(3.0);
		PackedCollection<?> biases = new PackedCollection<>(shape(c)).fill(0.0);
		PackedCollection<?> origBiases = new PackedCollection<>(biases);

		PackedCollection<?> result = new PackedCollection<>(shape(c));

		// CellularLayer layer = norm(shape(c), groups, false);
		CellularLayer layer = norm(groups, null, biases);
		((PropagationCell) layer.getBackward()).setLearningRate(cp(lr));
		((PropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().setReceptor(into(result));

		// Process.optimized(layer.getBackward().push(p(gradient))).get().run();
		layer.getBackward().push(p(gradient)).get().run();

		double eps = Hardware.getLocalHardware().getPrecision().epsilon();

		int groupSize = c / groups;

		for (int g = 0; g < groups; g++) {
			int start = g * groupSize;

			PackedCollection<?> xGroup = input.range(shape(groupSize), start);
			PackedCollection<?> dLdyGroup = gradient.range(shape(groupSize), start);

			double muG = xGroup.doubleStream().sum() / groupSize;
			double varG = variance(cp(xGroup)).evaluate().toDouble();
			double stdG = Math.sqrt(varG + eps);

			PackedCollection<?> xHatGroup = cp(xGroup).subtract(c(muG)).divide(c(stdG)).evaluate();

			PackedCollection<?> dLdBeta = dLdyGroup;
			PackedCollection<?> dLdHatXGroup = dLdyGroup;

			double dLdHatXGroupMean = dLdHatXGroup.doubleStream().sum() / groupSize;
			PackedCollection<?> dLdHatXGroupXHatGroup = cp(dLdHatXGroup).multiply(cp(xHatGroup)).evaluate();

			double dLdHatXGroupXHatGroupMean = dLdHatXGroupXHatGroup.doubleStream().sum() / groupSize;

			PackedCollection<?> dLdXGroup = cp(dLdHatXGroup)
					.subtract(c(dLdHatXGroupMean))
					.subtract(cp(xHatGroup).multiply(c(dLdHatXGroupXHatGroupMean)))
					.evaluate();
			for (int i = 0; i < groupSize; i++) {
				double expected = dLdXGroup.valueAt(i) / stdG;
				double actual = result.valueAt(start + i);
				log(expected + " vs " + actual);
				// assertEquals(expected, actual);

				expected = lr.toDouble() * dLdBeta.valueAt(i);
				actual = origBiases.valueAt(start + i) - biases.valueAt(start + i);
				log(expected + " vs " + actual);
				// assertEquals(expected, actual);
			}
		}
	}

	@Test
	public void normBackwardsTrainable() {
		if (skipLongTests) return;

		int c = 96;
		int groups = 6;

		PackedCollection<?> lr = pack(0.01);
		PackedCollection<?> input = new PackedCollection(shape(c)).fill(() -> Math.random() / 10.0);
		PackedCollection<?> gradient = new PackedCollection<>(shape(c)).fill(() -> Math.random() / 4.0);
		PackedCollection<?> weights = new PackedCollection<>(shape(c)).fill(0.5);
		PackedCollection<?> biases = new PackedCollection<>(shape(c)).fill(0.5);
		PackedCollection<?> origWeights = new PackedCollection<>(weights);
		PackedCollection<?> origBiases = new PackedCollection<>(biases);

		PackedCollection<?> result = new PackedCollection<>(shape(c));

		CellularLayer layer = norm(groups, weights, biases);
		((PropagationCell) layer.getBackward()).setLearningRate(cp(lr));
		((PropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().setReceptor(into(result));

		Process.optimized(layer.getBackward().push(p(gradient))).get().run();

		double eps = Hardware.getLocalHardware().getPrecision().epsilon();

		int groupSize = c / groups;

		for (int g = 0; g < groups; g++) {
			int start = g * groupSize;

			PackedCollection<?> xGroup = input.range(shape(groupSize), start);
			PackedCollection<?> dLdyGroup = gradient.range(shape(groupSize), start);

			double muG = xGroup.doubleStream().sum() / groupSize;
			double varG = variance(cp(xGroup)).evaluate().toDouble() + eps;
			double stdG = Math.sqrt(varG);

			PackedCollection<?> xHatGroup = cp(xGroup).subtract(c(muG)).divide(c(stdG)).evaluate();

			PackedCollection<?> dLdBeta = dLdyGroup;
			PackedCollection<?> dLdGamma = cp(dLdyGroup).multiply(cp(xHatGroup)).evaluate();

			PackedCollection<?> dLdHatXGroup = cp(dLdyGroup).multiply(cp(weights.range(shape(groupSize), start))).evaluate();

			double dLdHatXGroupMean = dLdHatXGroup.doubleStream().sum() / groupSize;
			PackedCollection<?> dLdHatXGroupXHatGroup = cp(dLdHatXGroup).multiply(cp(xHatGroup)).evaluate();

			double dLdHatXGroupXHatGroupMean = dLdHatXGroupXHatGroup.doubleStream().sum() / groupSize;

			PackedCollection<?> dLdXGroup = cp(dLdHatXGroup)
					.subtract(c(dLdHatXGroupMean))
					.subtract(cp(xHatGroup).multiply(c(dLdHatXGroupXHatGroupMean)))
					.evaluate();
			for (int i = 0; i < groupSize; i++) {
				log(dLdXGroup.valueAt(i) / stdG + " vs " + result.valueAt(start + i));
				assertEquals(dLdXGroup.valueAt(i) / stdG, result.valueAt(start + i));

				log(origWeights.valueAt(start + i) - weights.valueAt(start + i) + " vs " + lr.toDouble() * dLdGamma.valueAt(i));
				assertEquals(origWeights.valueAt(start + i) - weights.valueAt(start + i), lr.toDouble() * dLdGamma.valueAt(i));

				log(origBiases.valueAt(start + i) - biases.valueAt(start + i) + " vs " + lr.toDouble() * dLdBeta.valueAt(i));
				assertEquals(origBiases.valueAt(start + i) - biases.valueAt(start + i), lr.toDouble() * dLdBeta.valueAt(i));
			}
		}
	}

	@Test
	public void normModel() throws IOException {
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
