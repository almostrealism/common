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
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.layers.BackPropagationCell;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.GradientTestFeatures;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.function.Supplier;

public class NormTests implements LayerFeatures, GradientTestFeatures, TestFeatures {
	public static boolean enableRandom = true;
	public static double threshold = 0.005;

	protected static double[] values = {0.5, 1.5, 2.0};
	protected static int pos = 0;

	private static double random() {
		if (enableRandom) {
			return Math.random();
		}

		return values[pos++ % values.length];
	}

	protected Supplier<PackedCollection> randomInput(int size) {
		return () -> new PackedCollection(size).fill(() -> random() / 10.0);
	}

	protected Supplier<PackedCollection> randomGradient(int size) {
		return () -> new PackedCollection(shape(size)).fill(() -> 1 + (random() * 4.0));
	}

	protected void validate(int groups, int groupSize, int v,
							PackedCollection in, PackedCollection out,
							PackedCollection weights, PackedCollection biases) {
		int tot = groupSize * v;

		in = in.reshape(groups, groupSize, v);
		out = out.reshape(groups, groupSize, v);
		weights = weights == null ? null : weights.reshape(groups, groupSize, v);
		biases = biases == null ? null : biases.reshape(groups, groupSize, v);

		double eps = Hardware.getLocalHardware().epsilon();

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
					double o = d / Math.sqrt(variance + eps);
					if (weights != null) o = o * weights.valueAt(i, j, k);
					if (biases != null) o = o + biases.valueAt(i, j, k);

					assertEquals(o, out.valueAt(i, j, k));
				}
			}
		}
	}

	@Test
	public void normComputationNoWeights() {
		normComputation(12, 10, 4, null, null);
	}

	@Test
	public void normComputationMedium() {
		if (testDepth < 1) return;

		int c = 28;
		int v = 28 * 28;

		PackedCollection weights = new PackedCollection(c * v).randnFill();
		PackedCollection biases = new PackedCollection(c * v).randnFill();
		normComputation(c, v, 4, weights, biases);
	}

	public void normComputation(int c, int v, int groups, PackedCollection weights, PackedCollection biases) {
		double eps = Hardware.getLocalHardware().epsilon();

		TraversalPolicy shape = shape(c, v);

		PackedCollection o = new PackedCollection(shape.getTotalSize());
		o.fill(pos -> Math.random());

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, groups, shape.getTotalSize() / groups);
					CollectionProducer out = input
							.subtractMean(2)
							.divide(input.variance(2).add(c(eps)).sqrt())
							.reshape(-1, shape.getTotalSize());
					if (weights != null) out = out.multiply(cp(weights));
					if (biases != null) out = out.add(cp(biases));
					return out;
				},
				output -> {
					// TODO
				}, false, false, true);
	}

	@Test
	public void normComputationDeltaMedium() {
		if (skipKnownIssues) return;

		int c = 28;
		int v = 28 * 28;

		PackedCollection weights = new PackedCollection(c * v).randnFill();
		PackedCollection biases = new PackedCollection(c * v).randnFill();
		normComputationDelta(c, v, 4, weights, null);
	}

	public void normComputationDelta(int c, int v, int groups, PackedCollection weights, PackedCollection biases) {
		double eps = Hardware.getLocalHardware().epsilon();

		TraversalPolicy shape = shape(c, v);

		PackedCollection o = new PackedCollection(shape.getTotalSize());
		o.fill(pos -> Math.random());

		kernelTest(() -> {
					CollectionProducer input = cp(o).reshape(-1, groups, shape.getTotalSize() / groups);
					CollectionProducer out = input
							.subtractMean(2)
							.divide(input.variance(2).add(c(eps)).sqrt())
							.reshape(-1, shape.getTotalSize());
					if (weights != null) out = out.multiply(cp(weights));
					if (biases != null) out = out.add(cp(biases));
					out.describe();
					return out.delta(cp(o));
				},
				output -> {
					validate(groups, c / groups, v, o, output, weights, biases);
				}, false, false, true);
	}

	@Test
	public void normLayer() {
		int c = 12;
		int v = 10;
		int groups = 4;

		PackedCollection in = new PackedCollection(shape(c, v)).randnFill();
		PackedCollection out = new PackedCollection(shape(c, v));

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

		PackedCollection weights = new PackedCollection(c * v).randnFill();
		PackedCollection biases = new PackedCollection(c * v).randnFill();

		PackedCollection in = new PackedCollection(shape(c, v)).randnFill();
		PackedCollection out = new PackedCollection(shape(c, v));

		CellularLayer layer = norm(groups, weights, biases);
		layer.andThen(out);

		Process.optimized(layer.forward(cp(in))).get().run();
		out.traverse(1).print();

		int groupSize = c / groups;
		validate(groups, groupSize, v, in, out, weights, biases);
	}

	protected void normBackwards(int c, int groups) throws IOException {
		normBackwards(null, c, groups, randomInput(c));
	}

	protected void normBackwards(String name, int c, int groups,
								 Supplier<PackedCollection> inputSource) throws IOException {
		log("Total native programs = " + NativeCompiler.getTotalInstructionSets());

		PackedCollection lr = pack(0.01);
		PackedCollection input = inputSource.get();
		PackedCollection gradient = randomGradient(c).get();

		PackedCollection result = new PackedCollection(shape(c));

		CellularLayer layer = norm(shape(c), groups, false);
		((BackPropagationCell) layer.getBackward()).setParameterUpdate(ParameterUpdate.scaled(cp(lr)));
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().setReceptor(into(result));

		// Process.optimized(layer.getBackward().push(p(gradient))).get().run();
		run(name, layer.getBackward(), gradient);

		log("Total native programs after run = " + NativeCompiler.getTotalInstructionSets());

		double eps = Hardware.getLocalHardware().epsilon();

		int groupSize = c / groups;

		for (int g = 0; g < groups; g++) {
			int start = g * groupSize;

			PackedCollection xGroup = input.range(shape(groupSize), start);
			PackedCollection dLdyGroup = gradient.range(shape(groupSize), start);

			double muG = xGroup.doubleStream().sum() / groupSize;
			double varG = variance(cp(xGroup)).evaluate().toDouble();
			double stdG = Math.sqrt(varG + eps);

			PackedCollection xHatGroup = cp(xGroup).subtract(c(muG)).divide(c(stdG)).evaluate();

			PackedCollection dLdHatXGroup = dLdyGroup;

			double dLdHatXGroupMean = dLdHatXGroup.doubleStream().sum() / groupSize;
			PackedCollection dLdHatXGroupXHatGroup = cp(dLdHatXGroup).multiply(cp(xHatGroup)).evaluate();

			double dLdHatXGroupXHatGroupMean = dLdHatXGroupXHatGroup.doubleStream().sum() / groupSize;

			PackedCollection dLdXGroup = dlDxGroup(
					dLdHatXGroup, dLdHatXGroupMean,
					xHatGroup, dLdHatXGroupXHatGroupMean);
			for (int i = 0; i < groupSize; i++) {
				double expected = dLdXGroup.valueAt(i) / stdG;
				double actual = result.valueAt(start + i);
				assertSimilar(expected, actual);
			}
		}

		log("Total native programs after validate = " + NativeCompiler.getTotalInstructionSets());
	}

	@Test
	public void backwardsSmallLowVariance() throws IOException {
		normBackwards("backwardsSmallLowVariance", 2, 1,
				() -> new PackedCollection(shape(2)).fill(1.0, 1.01));
	}

	@Test
	public void backwardsSmall() throws IOException {
		normBackwards(2, 1);
	}

	@Test
	public void backwardsMedium() throws IOException {
		normBackwards(120, 4);
	}

	@Test
	public void backwardsProgressive() throws IOException {
		if (testDepth < 3) return;

		int c = 120;

		for (int i = 0; i < 3; i++) {
			normBackwards(c, 4);
			c = c - 20;
		}
	}

	@Test
	public void backwardsBiasSmallLowVariance() throws IOException {
		normBackwardsBias("backwardsBiasSmallLowVariance", 2, 1, true, 1.0, 1.01);
	}

	@Test
	public void backwardsBiasSmall1() throws IOException {
		normBackwardsBias("backwardsBiasSmall1", 2, 1);
	}

	@Test
	public void backwardsBiasSmall2() throws IOException {
		normBackwardsBias("backwardsBiasSmall2", 4, 1);
	}

	@Test
	public void backwardsBiasMedium1() throws IOException {
		if (testDepth < 2) return;

		normBackwardsBias("backwardsBiasMedium1", 8, 4);
	}

	@Test
	public void backwardsBiasMedium2() throws IOException {
		normBackwardsBias("backwardsBiasMedium2", 16, 4);
	}

	@Test
	public void backwardsBiasMedium3() throws IOException {
		if (testDepth < 1) return;
		if (testProfileIs(TestUtils.PIPELINE)) return;

		normBackwardsBias("backwardsBiasMedium3", 64, 1);
	}

	@Test
	public void backwardsBiasProgressive1() throws IOException {
		if (testDepth < 2) return;
		if (testProfileIs(TestUtils.PIPELINE)) return;

		backwardsBiasProgressive(2, 1, 5);
	}

	@Test
	public void backwardsBiasProgressive2() throws IOException {
		if (testDepth < 1) return;
		if (testProfileIs(TestUtils.PIPELINE)) return;

		backwardsBiasProgressive(32, 4, 3);
	}

	public void backwardsBiasProgressive(int c, int groups, int n) throws IOException {
		for (int i = 0; i < n; i++) {
			log("Iteration " + i + " c = " + c);
			normBackwardsBias(null, c, groups);
			c = c * 2;
		}
	}

	public void normBackwardsBias(String name, int c, int groups) throws IOException {
		normBackwardsBias(name, c, groups, true);
	}

	public void normBackwardsBias(String name, int c, int groups, boolean failFast) throws IOException {
		normBackwardsBias(name, c, groups, failFast, randomInput(c));
	}

	public void normBackwardsBias(String name, int c, int groups, boolean failFast, double x, double y) throws IOException {
		normBackwardsBias(name, c, groups, failFast, () -> new PackedCollection(shape(c)).fill(x, y));
	}

	public void normBackwardsBias(String name, int c, int groups, boolean failFast, Supplier<PackedCollection> inputSource) throws IOException {
		PackedCollection lr = pack(0.01);
		PackedCollection input = inputSource.get();
		PackedCollection gradient = randomGradient(c).get();
		PackedCollection biases = new PackedCollection(shape(c)).fill(0.0);
		PackedCollection origBiases = new PackedCollection(biases);

		PackedCollection result = new PackedCollection(shape(c));

		CellularLayer layer = norm(groups, null, biases);
		((BackPropagationCell) layer.getBackward()).setParameterUpdate(ParameterUpdate.scaled(cp(lr)));
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().setReceptor(into(result));
		run(name, layer.getBackward(), gradient);

		double loss = 0.0;

		int groupSize = c / groups;

		for (int g = 0; g < groups; g++) {
			int start = g * groupSize;

			PackedCollection dLdBeta = gradient.range(shape(groupSize), start);
			PackedCollection expectedGrad = normBackwards(
					input.range(shape(groupSize), start),
					gradient.range(shape(groupSize), start),
					null,
					origBiases.range(shape(groupSize), start));

			for (int i = 0; i < groupSize; i++) {
				double expected = expectedGrad.valueAt(i);
				double actual = result.valueAt(start + i);
				double diff = Math.abs(expected - actual);
				loss += diff;
				assertSimilar(expected, actual, threshold);

				expected = lr.toDouble() * dLdBeta.valueAt(i);
				actual = origBiases.valueAt(start + i) - biases.valueAt(start + i);
				diff = Math.abs(expected - actual);
				loss += diff;
				assertSimilar(expected, actual);
			}
		}

		double threshold = 2 * c * 1e-5;
		if (!failFast && loss > threshold) {
			Assert.fail(loss + " > " + threshold);
		}
	}

	@Test
	public void backwardsTrainableSmallLowVariance() throws IOException {
		normBackwardsTrainable("backwardsTrainableSmallLowVariance",
				2, 1, true, true,
				() -> new PackedCollection(shape(2)).fill(1.0, 1.01));
	}

	@Test
	public void backwardsTrainableSmall1() throws IOException {
		normBackwardsTrainable("backwardsTrainableSmall1", 2, 1);
	}

	@Test
	public void backwardsTrainableSmall2() throws IOException {
		if (testDepth < 1) return;

		ParallelProcess.explicitIsolationTargets.add(t -> {
			return t instanceof PackedCollectionEnumerate;
		});

		try {
			normBackwardsTrainable("backwardsTrainableSmall2", 3, 1);
		} finally {
			ParallelProcess.explicitIsolationTargets.clear();
		}
	}

	@Test
	public void backwardsTrainableMedium1() throws IOException {
		normBackwardsTrainable("backwardsTrainableMedium1", 8, 4);
	}

	@Test
	public void backwardsTrainableMedium2() throws IOException {
		if (testDepth < 1) return;

		normBackwardsTrainable("backwardsTrainableMedium2", 16, 4);
	}

	@Test
	public void backwardsTrainableLarge1() throws IOException {
		if (testDepth < 2) return;

		int c = 120;
		int groups = 4;
		normBackwardsTrainable("backwardsTrainableLarge1", c, groups);
	}

	@Test
	public void backwardsTrainableLarge2() throws IOException {
		if (testDepth < 1) return;

		int c = 96;
		int groups = 6;
		normBackwardsTrainable("backwardsTrainableLarge2", c, groups);
	}

	@Test
	public void backwardsTrainableProgressive() throws IOException {
		if (skipKnownIssues) return;
		if (testDepth < 3) return;
		if (testProfileIs(TestUtils.PIPELINE)) return;

		int c = 200;
		int groups = 4;

		while (c < 1400) {
			log("START c = " + c);
			normBackwardsTrainable("backwardsTrainable" + c, c, groups);
			c = c + 200;
		}
	}

	@Test
	public void backwardsTrainableVeryLarge1() throws IOException {
		if (testDepth < 2) return;
		if (testProfileIs(TestUtils.PIPELINE)) return;

		int c = 1600;
		int groups = 4;
		normBackwardsTrainable("backwardsTrainableVeryLarge1", c, groups);
	}

	@Test
	public void backwardsTrainableVeryLarge2() throws IOException {
		if (skipKnownIssues) return;
		if (testDepth < 3) return;
		if (testProfileIs(TestUtils.PIPELINE)) return;

		int c = 3456;
		int groups = 4;
		normBackwardsTrainable("backwardsTrainableVeryLarge2", c, groups);
	}

	@Test
	public void backwardsTrainableVeryLarge3() throws IOException {
		if (skipKnownIssues) return;
		if (skipLongTests || testDepth < 3) return;
		if (testProfileIs(TestUtils.PIPELINE)) return;

		int c = 21952;
		int groups = 4;
		normBackwardsTrainable("backwardsTrainableVeryLarge3", c, groups, false);
	}

	protected void normBackwardsTrainable(String name, int c, int groups) throws IOException {
		normBackwardsTrainable(name, c, groups, true);
	}

	protected void normBackwardsTrainable(String name, int c, int groups, boolean validate) throws IOException {
		normBackwardsTrainable(name, c, groups, validate, true);
	}

	protected void normBackwardsTrainable(String name, int c, int groups, boolean validate, boolean failFast) throws IOException {
		normBackwardsTrainable(name, c, groups, validate, failFast, randomInput(c));
	}

	protected void normBackwardsTrainable(String name, int c, int groups,
										  boolean validate, boolean failFast,
										  Supplier<PackedCollection> inputSource) throws IOException {
		double w = 1.0; // 0.5;
		double b = 0.0; // 0.5;

		PackedCollection lr = pack(0.01);
		PackedCollection input = inputSource.get();
		PackedCollection gradient = randomGradient(c).get();

		PackedCollection weights = new PackedCollection(shape(c)).fill(w);
		PackedCollection biases = new PackedCollection(shape(c)).fill(b);
		PackedCollection origWeights = new PackedCollection(weights);
		PackedCollection origBiases = new PackedCollection(biases);

		PackedCollection result = new PackedCollection(shape(c));

		CellularLayer layer = norm(groups, weights, biases);
		((BackPropagationCell) layer.getBackward()).setParameterUpdate(ParameterUpdate.scaled(cp(lr)));
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().setReceptor(into(result));
		run(name, layer.getBackward(), gradient);

		if (!validate) return;

		double eps = Hardware.getLocalHardware().epsilon();

		double loss = 0.0;

		int groupSize = c / groups;

		Evaluable<PackedCollection> xHatGroupEval =
				cv(shape(groupSize), 0)
					.subtract(cv(shape(1), 1))
						.divide(cv(shape(1), 2)).get();
		Evaluable<PackedCollection> dLdGammaEval =
				cv(shape(groupSize), 0)
					.multiply(cv(shape(groupSize), 1)).get();

		for (int g = 0; g < groups; g++) {
			int start = g * groupSize;

			PackedCollection xGroup = input.range(shape(groupSize), start);
			PackedCollection dLdyGroup = gradient.range(shape(groupSize), start);

			double muG = xGroup.doubleStream().sum() / groupSize;
			double varG = Process.optimized(variance(cp(xGroup))).get().evaluate().toDouble();
			double stdG = Math.sqrt(varG + eps);

			PackedCollection xHatGroup = xHatGroupEval.evaluate(xGroup, pack(muG), pack(stdG));

			PackedCollection dLdBeta = dLdyGroup;
			PackedCollection dLdGamma = dLdGammaEval.evaluate(dLdyGroup, xHatGroup);

			PackedCollection expectedGrad = normBackwards(
					input.range(shape(groupSize), start),
					gradient.range(shape(groupSize), start),
					origWeights.range(shape(groupSize), start),
					origBiases.range(shape(groupSize), start));

			for (int i = 0; i < groupSize; i++) {
				double expected = expectedGrad.valueAt(i);
				double actual = result.valueAt(start + i);
				double diff = Math.abs(expected - actual);
				loss += diff;
				assertSimilar(expected, actual, threshold);

				expected = lr.toDouble() * dLdGamma.valueAt(i);
				actual = origWeights.valueAt(start + i) - weights.valueAt(start + i);
				diff = Math.abs(expected - actual);
				loss += diff;
				assertSimilar(expected, actual, threshold);

				expected = lr.toDouble() * dLdBeta.valueAt(i);
				actual = origBiases.valueAt(start + i) - biases.valueAt(start + i);
				diff = Math.abs(expected - actual);
				loss += diff;
				assertSimilar(expected, actual, threshold);
			}
		}

		log("Loss = " + loss);

		double threshold = 3 * c * 1e-5;
		if (!failFast && loss > threshold) {
			Assert.fail(loss + " > " + threshold);
		}
	}

	protected void run(String name, Cell<PackedCollection> cell, PackedCollection input) throws IOException {
		Supplier<Runnable> op = Process.optimized(cell.push(p(input)));

		if (name == null) {
			op.get().run();
		} else {
			profile(name, op).save("results/" + name + ".xml");
		}
	}

	@Test
	public void normModel() throws IOException {
		int c = 12;
		int v = 10;
		int groups = 4;

		PackedCollection weights = new PackedCollection(c * v).randnFill();
		PackedCollection biases = new PackedCollection(c * v).randnFill();

		PackedCollection in = new PackedCollection(shape(c, v)).randnFill();

		Model model = new Model(shape(c, v));
		model.add(norm(groups, weights, biases));

		OperationProfileNode profile = initKernelMetrics(new OperationProfileNode());

		try {
			CompiledModel compiled = model.compile(profile);
			PackedCollection out = compiled.forward(in);
			validate(groups, c / groups, v, in, out, weights, biases);
		} finally {
			profile.save("results/normModel.xml");
		}
	}
}
