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

package org.almostrealism.collect.computations.test;

import io.almostrealism.code.ComputationBase;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Signature;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public class CollectionAddTests implements TestFeatures {
	int iter = 1000000;
	int size = 150000;
	int parallelism = KernelPreferences.getCpuParallelism();

	@Override
	public <T> Producer<T> v(TraversalPolicy shape, int argIndex) {
		return new PassThroughProducer(shape, argIndex, true);
	}

	public void add(ComputeRequirement... req) {
		if (testDepth < 3) return;

		log("Native parallelism = " + KernelPreferences.getCpuParallelism());

		CollectionProducer<PackedCollection<?>> add = add(v(shape(1), 0), v(shape(1), 1));
		((ComputationBase) add).setComputeRequirements(List.of(req));
		log("signature = " + Signature.of(add) + ", req = " + ((ComputationBase) add).getComputeRequirements());

		Evaluable<PackedCollection<?>> ev = add.get();

		PackedCollection<?> a = new PackedCollection<>(shape(size));
		PackedCollection<?> b = new PackedCollection<>(shape(size));

		long start = System.currentTimeMillis();
		for (int i = 0; i < iter; i++) {
			ev.evaluate(a.each(), b.each());
		}

		log("total time = " + (System.currentTimeMillis() - start) + "ms");
	}

	@Test
	public void cpuAdd() {
		add(ComputeRequirement.CPU);
	}

	@Test
	public void gpuAdd() {
		add(ComputeRequirement.GPU);
	}

	@Test
	public void javaAdd() throws InterruptedException {
		if (testDepth < 3) return;

		double a[] = new double[size];
		double b[] = new double[size];

		CountDownLatch latch = new CountDownLatch(parallelism);

		Function<Integer, Runnable> r = id -> () -> {
			double out[] = new double[size];

			for (int it = 0; it < iter; it++) {
				for (int i = id; i < size; i += parallelism) {
					out[i] = a[i] + b[i];
				}
			}

			latch.countDown();
		};

		ExecutorService exec = Executors.newFixedThreadPool(parallelism);

		long start = System.currentTimeMillis();
		for (int i = 0; i < parallelism; i++) {
			exec.submit(r.apply(i));
		}

		latch.await();
		log("total time = " + (System.currentTimeMillis() - start) + "ms");

		exec.shutdownNow();
	}
}
