/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.graph.model.test;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.TrainingResult;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Profiled reproduction of the training step exercised by
 * {@code SyntheticNormTrainingTest.denseMultiLayerWithNorm}, with a fixed
 * number of epochs so that the per-step cost of a training iteration can be
 * measured and read from an operation profile.
 *
 * <p>The number of epochs can be raised with the {@code AR_PROFILE_EPOCHS}
 * system property when a longer steady-state measurement is wanted. Besides
 * the wall time per step, the test reports the Metal dispatch and
 * command-buffer commit counts per step and the distribution of the
 * operations whose host waits forced a commit, which is where the time of
 * a small model's training step goes on that backend.</p>
 */
public class TrainingStepProfileTest extends TestSuiteBase implements ModelTestFeatures {
	/** Fixed coefficients for the target function. */
	private final PackedCollection coeff = pack(0.24, -0.1, 0.36);

	/** Element-wise linear target function. */
	private final UnaryOperator<PackedCollection> linearFunc = in -> {
		int n = in.getMemLength();
		return cp(coeff).valueAt(integers(0, n).mod(coeff.getMemLength()))
				.multiply(cp(in).reshape(shape(n)))
				.evaluate().reshape(in.getShape());
	};

	/**
	 * Runs a fixed number of epochs of the multi-layer norm model under a profile,
	 * reports the per-step cost, and checks that the training loss went down.
	 */
	// TODO(review): missing @TestDepth, unlike other training tests in this package (see denseMultiLayerWithNorm)
	@Test(timeout = 300000)
	public void profileTrainingStep() throws IOException {
		int inputSize = 6;
		int hiddenSize = 6;
		int outputSize = 3;
		int epochs = Integer.parseInt(System.getProperty("AR_PROFILE_EPOCHS", "3"));
		int steps = 260;

		SequentialBlock block = new SequentialBlock(shape(inputSize));
		block.add(dense(inputSize, hiddenSize));
		block.add(norm(1));
		block.add(dense(hiddenSize, hiddenSize));
		block.add(norm(1));
		block.add(dense(hiddenSize, outputSize));

		Model model = new Model(shape(inputSize), 1e-5);
		model.add(block);

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection(shape(inputSize)))
				.map(input -> {
					rand(input.getShape()).multiply(10.0).add(1.0)
							.multiply(rand(input.getShape()).add(-0.5))
							.into(input.traverseEach()).evaluate();
					return input;
				})
				.map(input -> ValueTarget.of(input, linearFunc.apply(input)))
				.collect(Collectors.toList()));

		OperationProfileNode profile = new OperationProfileNode("trainingStep");
		CompiledModel compiled = model.compile(profile);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			ModelOptimizer optimizer = new ModelOptimizer(compiled, data);
			optimizer.setLogFrequency(1);
			optimizer.setLossTarget(0.0);

			MetalCommandRunner.resetBatchSizeCounters();
			long start = System.nanoTime();
			TrainingResult result = optimizer.optimize(epochs);
			long elapsed = (System.nanoTime() - start) / 1_000_000;
			log("epochs=" + epochs + " steps=" + steps + " elapsedMs=" + elapsed
					+ " msPerStep=" + ((double) elapsed / (epochs * steps)));
			log("totalDispatchCount=" + MetalCommandRunner.totalDispatchCount.get()
					+ " totalCommitCount=" + MetalCommandRunner.totalCommitCount.get()
					+ " meanBatchSize=" + MetalCommandRunner.meanBatchSize()
					+ " dispatchesPerStep=" + (MetalCommandRunner.totalDispatchCount.get() / (double) (epochs * steps))
					+ " commitsPerStep=" + (MetalCommandRunner.totalCommitCount.get() / (double) (epochs * steps)));
			log(MetalCommandRunner.hostCompleteRequesters.summary());

			List<Double> losses = result.getTrainLossHistory();
			Assert.assertEquals(epochs, losses.size());
			Assert.assertTrue(Double.isFinite(result.getFinalTrainLoss()));
			Assert.assertTrue("loss did not decrease: " + losses,
					result.getFinalTrainLoss() < losses.get(0));
		} finally {
			Hardware.getLocalHardware().clearProfile();
			profile.save("results/trainingStep.xml");
		}
	}
}
