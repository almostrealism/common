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
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.PropagationCell;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.function.Supplier;

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

	protected void normBackwards(int c, int groups) {
		normBackwards(c, groups, randomInput(c));
	}

	protected void normBackwards(int c, int groups, Supplier<PackedCollection<?>> inputSource) {
		PackedCollection<?> lr = pack(0.01);
		PackedCollection<?> input = inputSource.get();
		PackedCollection<?> gradient = randomGradient(c).get();

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

			PackedCollection<?> dLdXGroup = dlDxGroup(
					dLdHatXGroup, dLdHatXGroupMean,
					xHatGroup, dLdHatXGroupXHatGroupMean);
			for (int i = 0; i < groupSize; i++) {
				double expected = dLdXGroup.valueAt(i) / stdG;
				double actual = result.valueAt(start + i);

				log(expected + " vs " + actual);
				assertEquals(expected, actual);
			}
		}
	}

	@Test
	public void backwardsSmallLowVariance() {
		normBackwards(2, 1, () -> new PackedCollection(shape(2)).fill(1.0, 1.01));
	}

	@Test
	public void backwardsSmall() {
		normBackwards(2, 1);
	}

	@Test
	public void backwardsProgressive() {
		if (skipLongTests) return;

		int c = 120;

		for (int i = 0; i < 3; i++) {
			normBackwards(c, 4);
			c = c - 20;
		}
	}

	@Test
	public void backwardsBiasSmallLowVariance() {
		if (skipKnownIssues) return;
		normBackwardsBias(2, 1, true, 1.0, 1.01);
	}

	@Test
	public void backwardsBiasSmall1() {
		normBackwardsBias(2, 1, true);
	}

	@Test
	public void backwardsBiasSmall2() {
		normBackwardsBias(4, 1, true);
	}

	@Test
	public void backwardsBiasMedium1() {
		normBackwardsBias(8, 4, true);
	}

	@Test
	public void backwardsBiasMedium2() {
		normBackwardsBias(16, 4, true);
	}

	@Test
	public void backwardsBiasMedium3() {
		normBackwardsBias(64, 1, false);
	}

	@Test
	public void backwardsBiasProgressive1() {
		backwardsBiasProgressive(2, 1, 5);
	}

	@Test
	public void backwardsBiasProgressive2() {
		if (skipKnownIssues) return;

		backwardsBiasProgressive(32, 4, 3);
	}

	public void backwardsBiasProgressive(int c, int groups, int n) {
		for (int i = 0; i < n; i++) {
			log("Iteration " + i + " c = " + c);
			normBackwardsBias(c, groups, false);
			c = c * 2;
		}
	}

	public void normBackwardsBias(int c, int groups, boolean failFast) {
		normBackwardsBias(c, groups, failFast, randomInput(c));
	}

	public void normBackwardsBias(int c, int groups, boolean failFast, double x, double y) {
		normBackwardsBias(c, groups, failFast, () -> new PackedCollection(shape(c)).fill(x, y));
	}

	public void normBackwardsBias(int c, int groups, boolean failFast, Supplier<PackedCollection<?>> inputSource) {
		PackedCollection<?> lr = pack(0.01);
		PackedCollection<?> input = inputSource.get();
//		PackedCollection<?> gradient = new PackedCollection<>(shape(c)).fill(3.0);
		PackedCollection<?> gradient = randomGradient(c).get();
		PackedCollection<?> biases = new PackedCollection<>(shape(c)).fill(0.0);
		PackedCollection<?> origBiases = new PackedCollection<>(biases);

		PackedCollection<?> result = new PackedCollection<>(shape(c));

		CellularLayer layer = norm(groups, null, biases);
		((PropagationCell) layer.getBackward()).setLearningRate(cp(lr));
		((PropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().setReceptor(into(result));
		run(layer.getBackward(), gradient);

		double eps = Hardware.getLocalHardware().getPrecision().epsilon();

		double loss = 0.0;

		int groupSize = c / groups;
		int failures[][] = new int[2][2];

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

			PackedCollection<?> dLdXGroup = dlDxGroup(
					dLdHatXGroup, dLdHatXGroupMean,
					xHatGroup, dLdHatXGroupXHatGroupMean);
			for (int i = 0; i < groupSize; i++) {
				double expected = dLdXGroup.valueAt(i) / stdG;
				double actual = result.valueAt(start + i);
				double diff = Math.abs(expected - actual);
				loss += diff;

				log("Gradient " + expected + " vs " + actual);
				if (diff > 1e-5) {
					failures[0][0]++;
					if (diff > 1e-4) failures[0][1]++;
					if (failFast) Assert.assertEquals(expected, actual, 1e-5);
				}

				expected = lr.toDouble() * dLdBeta.valueAt(i);
				actual = origBiases.valueAt(start + i) - biases.valueAt(start + i);
				diff = Math.abs(expected - actual);
				loss += diff;

				log("Bias " + expected + " vs " + actual);
				if (diff > 1e-5) {
					failures[1][0]++;
					if (diff > 1e-4) failures[1][1]++;
					if (failFast) Assert.assertEquals(expected, actual, 1e-5);
				}
			}
		}

		log("Loss = " + loss);
		log("Gradient failures: " + failures[0][0] + " (" + failures[0][1] + ")");
		log("Bias failures: " + failures[1][0] + " (" + failures[1][1] + ")");

		double threshold = 2 * c * 1e-5;
		if (!failFast && loss > threshold) {
			Assert.fail(loss + " > " + threshold);
		}
	}

	@Test
	public void backwardsTrainableSmallLowVariance() {
		if (skipKnownIssues) return;
		normBackwardsTrainable(2, 1, true, () -> new PackedCollection(shape(2)).fill(1.0, 1.01));
	}

	@Test
	public void backwardsTrainableSmall() {
		normBackwardsTrainable(2, 1, true);
	}

	@Test
	public void backwardsTrainableMedium1() {
		normBackwardsTrainable(8, 4, true);
	}

	@Test
	public void backwardsTrainableMedium2() {
		normBackwardsTrainable(16, 4, false);
	}

	@Test
	public void backwardsTrainableProgressive() {
		if (skipKnownIssues) return;

		int c = 32;
		int groups = 4;

		for (int i = 0; i < 3; i++) {
			log("Iteration " + i + " c = " + c);
			normBackwardsTrainable(c, groups, false);
			c = c * 2;
		}
	}

	@Test
	public void backwardsTrainableLarge1() {
		if (skipLongTests || skipKnownIssues) return;

		int c = 120;
		int groups = 4;
		normBackwardsTrainable(c, groups, false);
	}

	@Test
	public void backwardsTrainableLarge2() {
		if (skipKnownIssues) return;

		int c = 96;
		int groups = 6;
		normBackwardsTrainable(c, groups, false);
	}

	protected void normBackwardsTrainable(int c, int groups, boolean failFast) {
//		normBackwardsTrainable(c, groups, randomInput(c));
		normBackwardsTrainable(c, groups, failFast, () -> new PackedCollection(shape(c)).fill(() -> (Math.random() + 1) * 10.0));
	}

	protected void normBackwardsTrainable(int c, int groups, boolean failFast, Supplier<PackedCollection<?>> inputSource) {
		PackedCollection<?> lr = pack(0.01);
		PackedCollection<?> input = inputSource.get();
		PackedCollection<?> gradient = randomGradient(c).get();

//		PackedCollection<?> weights = new PackedCollection<>(shape(c)).fill(0.5);
		PackedCollection<?> weights = new PackedCollection<>(shape(c)).fill(1.0);
		PackedCollection<?> origWeights = new PackedCollection<>(weights);

//		PackedCollection<?> biases = new PackedCollection<>(shape(c)).fill(0.5);
		PackedCollection<?> biases = new PackedCollection<>(shape(c)).fill(0.0);
		PackedCollection<?> origBiases = new PackedCollection<>(biases);

		PackedCollection<?> result = new PackedCollection<>(shape(c));

		CellularLayer layer = norm(groups, weights, biases);
		((PropagationCell) layer.getBackward()).setLearningRate(cp(lr));
		((PropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().setReceptor(into(result));
		run(layer.getBackward(), gradient);

		double eps = Hardware.getLocalHardware().getPrecision().epsilon();

		double loss = 0.0;

		int groupSize = c / groups;
		int failures[][] = new int[3][2];

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

			PackedCollection<?> dLdXGroup = dlDxGroup(
					dLdHatXGroup, dLdHatXGroupMean,
					xHatGroup, dLdHatXGroupXHatGroupMean);
			for (int i = 0; i < groupSize; i++) {
				double expected = dLdXGroup.valueAt(i) / stdG;
				double actual = result.valueAt(start + i);
				double diff = Math.abs(expected - actual);
				loss += diff;

				log(expected + " vs " + actual);
				if (diff > 1e-5) {
					failures[0][0]++;
					if (diff > 1e-4) failures[0][1]++;
					if (failFast) Assert.assertEquals(expected, actual, 1e-5);
				}

				expected = lr.toDouble() * dLdGamma.valueAt(i);
				actual = origWeights.valueAt(start + i) - weights.valueAt(start + i);
				diff = Math.abs(expected - actual);
				loss += diff;

				log(expected + " vs " + actual);
				if (diff > 1e-5) {
					failures[1][0]++;
					if (diff > 1e-4) failures[1][1]++;
					if (failFast) Assert.assertEquals(expected, actual, 1e-5);
				}

				expected = lr.toDouble() * dLdBeta.valueAt(i);
				actual = origBiases.valueAt(start + i) - biases.valueAt(start + i);
				diff = Math.abs(expected - actual);
				loss += diff;

				log(expected + " vs " + actual);
				if (diff > 1e-5) {
					failures[2][0]++;
					if (diff > 1e-4) failures[2][1]++;
					if (failFast) Assert.assertEquals(expected, actual, 1e-5);
				}
			}
		}

		log("Loss = " + loss);
		log("Gradient failures: " + failures[0][0] + " (" + failures[0][1] + ")");
		log("Weight failures: " + failures[1][0] + " (" + failures[1][1] + ")");
		log("Bias failures: " + failures[2][0] + " (" + failures[2][1] + ")");

		double threshold = 3 * c * 1e-5;
		if (!failFast && loss > threshold) {
			Assert.fail(loss + " > " + threshold);
		}
	}

	protected void run(Cell<PackedCollection<?>> cell, PackedCollection<?> input) {
		Process.optimized(cell.push(p(input))).get().run();
	}

	protected Supplier<PackedCollection<?>> randomInput(int size) {
		return () -> new PackedCollection<>(size).fill(() -> Math.random() / 10.0);
	}

	protected Supplier<PackedCollection<?>> randomGradient(int size) {
		return () -> new PackedCollection<>(shape(size)).fill(() -> Math.random() / 4.0);
	}

	private PackedCollection<?> dlDxGroup(PackedCollection<?> dLdHatXGroup,
										  double dLdHatXGroupMean,
										  PackedCollection<?> xHatGroup,
										  double dLdHatXGroupXHatGroupMean) {
		return cp(dLdHatXGroup)
				.subtract(c(dLdHatXGroupMean))
				.subtract(cp(xHatGroup).multiply(c(dLdHatXGroupXHatGroupMean)))
				.evaluate();
	}

	@Test
	public void normModel() throws IOException {
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
