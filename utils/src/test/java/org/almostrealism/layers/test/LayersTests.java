/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.stats.DistributionFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class LayersTests implements LayerFeatures, DistributionFeatures, TestFeatures {
	private static final int SIZE = 768;

	private float cpuOut[];
	private float gpuOut[];
	private double cpuSum = -1.0;
	private double gpuSum = -1.0;

	@Test
	public void exponent() {
		PackedCollection<?> in = new PackedCollection<>(SIZE).traverseEach();
		in.fill(pos -> Math.random());

		PackedCollection<?> weights = new PackedCollection<>(SIZE).traverseEach();
		weights.fill(pos -> Math.random());

		PackedCollection<?> cpuOut = new PackedCollection<>(SIZE);
		PackedCollection<?> gpuOut = new PackedCollection<>(SIZE);

		verboseLog(() -> {
			OperationList cop = new OperationList();
			cop.setComputeRequirements(List.of(ComputeRequirement.CPU));
			cop.add(a(p(cpuOut), pow(p(in), p(weights))));

			OperationList gop = new OperationList();
			gop.setComputeRequirements(List.of(ComputeRequirement.GPU));
			gop.add(a(p(gpuOut), pow(p(in), p(weights))));

			cop.get().run();
			gop.get().run();

			float cpu[] = new float[SIZE];
			cpuOut.getMem(0, cpu, 0, SIZE);

			float gpu[] = new float[SIZE];
			gpuOut.getMem(0, gpu, 0, SIZE);

			for (int i = 0; i < SIZE; i++) {
				if (gpu[i] != cpu[i]) {
					// throw new RuntimeException("Mismatch at " + i + ": " + gpu[i] + " vs " + cpu[i]);
				}
			}
		});
	}

	@Test
	public void rmsnorm() {
		PackedCollection<?> in = new PackedCollection<>(shape(SIZE));
		in.fill(pos -> Math.random());

		PackedCollection<?> weights = new PackedCollection<>(shape(SIZE));
		weights.fill(pos -> Math.random());

		SequentialBlock cpuModel = new SequentialBlock(shape(SIZE));
		cpuModel.add(rmsnorm(weights));
		cpuModel.getForward().setReceptor(out -> () -> () -> {
			PackedCollection<?> o = out.get().evaluate();
			cpuOut = new float[SIZE];
			o.getMem(0, cpuOut, 0, SIZE);
		});

		SequentialBlock gpuModel = new SequentialBlock(shape(SIZE));
		gpuModel.add(rmsnorm(weights, ComputeRequirement.GPU));
		gpuModel.getForward().setReceptor(out -> () -> () -> {
			PackedCollection<?> o = out.get().evaluate();
			gpuOut = new float[SIZE];
			o.getMem(0, gpuOut, 0, SIZE);
		});

		OperationList cop = ((OperationList) cpuModel.getForward().push(p(in)));
		cop = cop.flatten();
		cop = (OperationList) cop.optimize();
		cop.get().run();

		OperationList gop = ((OperationList) gpuModel.getForward().push(p(in)));
		gop = gop.flatten();
		gop = (OperationList) gop.optimize();
		gop.get().run();

		System.out.println("CPU vs GPU = " + cpuSum + " vs " + gpuSum);

		for (int i = 0; i < SIZE; i++) {
			assertEquals(gpuOut[i], cpuOut[i]);
		}

		for (int i = 0; i < SIZE; i++) {
			if (gpuOut[i] != cpuOut[i]) {
				// throw new RuntimeException("Mismatch at " + i + ": " + gpuOut[i] + " vs " + cpuOut[i]);
			}
		}
	}

	@Test
	public void dense() {
		if (skipKnownIssues) return;

		int size = 1800;
		int nodes = 10;
		int steps = 100;

		Model model = new Model(shape(size));
		model.add(dense(nodes));

		initKernelMetrics();
		OperationProfile profile = new OperationProfile("Model");

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(size)))
				.map(input -> input.fill(pos -> 1 + 2 * Math.random()))
				.map(input -> ValueTarget.of(input, input))
				.collect(Collectors.toList()));
		ModelOptimizer train = new ModelOptimizer(model, profile, data);
		log("Model compiled");

		try {
			train.optimize(1);
		} finally {
			logKernelMetrics(profile);
		}
	}

	@Test
	public void siluTrain() throws IOException {
		if (!CollectionFeatures.enableExponentComputation && skipKnownIssues) return;
		if (testDepth < 3) return;

		int size = 21952;
		int steps = 1;

		Model model = new Model(shape(size));
		model.add(silu());

		initKernelMetrics();
		OperationProfileNode profile = new OperationProfileNode("Silu Model");

		Supplier<Dataset<?>> data = () -> Dataset.of(IntStream.range(0, steps)
				.mapToObj(i -> new PackedCollection<>(shape(size)))
				.map(input -> input.fill(pos -> 1 + 2 * Math.random()))
				.map(input -> ValueTarget.of(input, input))
				.collect(Collectors.toList()));

		try {
			CompiledModel compiled = model.compile(true, true);
			log("Model compiled");

			ModelOptimizer train = new ModelOptimizer(compiled, data);
			profile(profile, () -> train.optimize(1))
					.save("results/siluTrain_" + size + ".xml");
		} finally {
			logKernelMetrics(profile);
		}
	}
}
