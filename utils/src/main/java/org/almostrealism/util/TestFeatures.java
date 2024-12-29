/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.util;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.TraversableRepeatedProducerComputation;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.kernel.KernelSeriesCache;
import org.almostrealism.io.Console;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

public interface TestFeatures extends CodeFeatures, TensorTestFeatures, TestSettings {
	Console console = Console.root.child();

	default void print(int rows, int colWidth, PackedCollection<?> value) {
		if (value.getShape().getTotalSize() > (rows * colWidth)) {
			value = value.range(shape(rows * colWidth), 0);
		}

		value.reshape(shape(rows, colWidth).traverse()).print();
		System.out.println("--");
	}

	default void assertEquals(Scalar a, Scalar b) {
		assertEquals(a.getValue(), b.getValue());
	}

	default void assertEquals(double a, Scalar b) {
		assertEquals(a, b.getValue());
	}

	default void assertEquals(double a, double b) {
		assertEquals(a, b, true);
	}

	default void assertNotEquals(Scalar a, Scalar b) {
		assertNotEquals(a.getValue(), b.getValue());
	}

	default void assertNotEquals(double a, Scalar b) {
		assertNotEquals(a, b.getValue());
	}

	default void assertNotEquals(double a, double b) {
		assertEquals(a, b, false);
	}

	private static void assertEquals(double a, double b, boolean positive) {
//		double gap = Hardware.getLocalHardware().isDoublePrecision() ? Math.pow(10, -10) : Math.pow(10, -4);
//		double fallbackGap = Math.pow(10, -3);
		double gap = Math.pow(10, 3) * Hardware.getLocalHardware().getPrecision().epsilon(true);
		double fallbackGap = 10 * gap;

		if (Math.abs(a - b) >= gap) {
			if (positive) {
				if (Math.abs(a - b) >= fallbackGap) {
					System.err.println("TestFeatures: " + b + " != " + a);
					throw new AssertionError();
				} else {
					System.out.println("TestFeatures: " + b + " != " + a);
				}
			}
		} else if (!positive) {
			System.err.println("TestFeatures: " + b + " == " + a);
			throw new AssertionError();
		}
	}

	default void assertSimilar(double a, double b) {
		assertSimilar(a, b, 0.001);
	}

	default void assertSimilar(double a, double b, double r) {
		double gap = Math.max(Math.abs(a), Math.abs(b));
		double eps = Hardware.getLocalHardware().getPrecision().epsilon();
		double comp = Math.max(eps, r * gap);

		double c = Math.abs(a - b);

		if (c >= comp) {
			double s = c / gap;
			warn(b + " != " + a + " (" + s + " > " + r + ")");
			throw new AssertionError();
		}
	}

	default void kernelTest(Supplier<? extends Producer<PackedCollection<?>>> supply,
							Consumer<PackedCollection<?>> validate) {
		kernelTest(supply, validate, true, true, true);
	}

	default OperationProfileNode kernelTest(String name,
											Supplier<? extends Producer<PackedCollection<?>>> supply,
											Consumer<PackedCollection<?>> validate) {
		return kernelTest(name, supply, validate, true, true, true);
	}

	default void kernelTest(Supplier<? extends Producer<PackedCollection<?>>> supply,
							Consumer<PackedCollection<?>> validate,
							boolean kernel, boolean operation, boolean optimized) {
		kernelTest(null, supply, validate, kernel, operation, optimized);
	}

	default OperationProfileNode kernelTest(String name,
							Supplier<? extends Producer<PackedCollection<?>>> supply, Consumer<PackedCollection<?>> validate,
							boolean kernel, boolean operation, boolean optimized) {
		OperationProfileNode profile = name == null ? null : new OperationProfileNode(name);

		AtomicReference<PackedCollection<?>> outputRef = new AtomicReference<>();

		if (kernel) {
			System.out.println("TestFeatures: Running kernel evaluation...");
			Producer<PackedCollection<?>> p = supply.get();
			profile(profile, () -> {
				PackedCollection<?> output = p.get().evaluate();
				log("Output Shape = " + output.getShape() +
						" [" + output.getShape().getCountLong() + "x" + output.getShape().getSize() + "]");
				log("Validating kernel output...");
				validate.accept(output);
				outputRef.set(output);
			});
		} else {
			outputRef.set(new PackedCollection<>(shape(supply.get())));
		}

		if (operation) {
			outputRef.get().clear();

			PackedCollection<?> output = outputRef.get();

			log("Running kernel operation...");
			OperationList op = new OperationList();
			op.add(output.getAtomicMemLength(), supply.get(), p(output));
			profile(profile, op);
			log("Validating kernel output...");
			validate.accept(output);
		}

		if (optimized) {
			outputRef.get().clear();

			PackedCollection<?> output = outputRef.get();
			PackedCollection<?> dest = new PackedCollection<>(output.getShape());

			log("Running optimized kernel operation...");
			OperationList op = new OperationList();
			op.add(output.getAtomicMemLength(), supply.get(), p(output));
			op.add(copy(p(output), p(dest), output.getMemLength()));

			ParallelProcess<?, Runnable> p = op.optimize();
			profile(profile, p);
			log("Validating optimized kernel output...");
			validate.accept(output);
			validate.accept(dest);
		}

		return profile;
	}

	default void initKernelMetrics() {
		initKernelMetrics(new OperationProfile(null, "HardwareOperator",
				OperationProfile.appendContext(OperationMetadata::getDisplayName)));
	}

	default <T extends OperationProfile> T initKernelMetrics(T profile) {
		Hardware.getLocalHardware().assignProfile(profile);
		AcceleratedComputationOperation.clearTimes();
		return profile;
	}

	default void logKernelMetrics() {
		logKernelMetrics(null);
	}

	default void logKernelMetrics(OperationProfile profile) {
		if (profile != null) profile.print();

		AcceleratedComputationOperation.printTimes();
		log("KernelSeriesCache min nodes - " + KernelSeriesCache.minNodeCountMatch +
				" (match) | " + KernelSeriesCache.minNodeCountCache + " (cache)");
		log("KernelSeriesCache size = " + KernelSeriesCache.defaultMaxExpressions +
				" expressions | " + KernelSeriesCache.defaultMaxEntries + " entries | "
				+ (KernelSeriesCache.enableCache ? "on" : "off"));
		log("Expression kernelSeq cache is " + (ScopeSettings.enableKernelSeqCache ? "on" : "off"));
		log("TraversableRepeatedProducerComputation isolation count threshold = " +
				TraversableRepeatedProducerComputation.isolationCountThreshold);
	}

	default void verboseLog(Runnable r) {
		if (TestSettings.verboseLogs) {
			HardwareOperator.verboseLog(r);
		} else {
			r.run();
		}
	}

	default Predicate<Process> operationFilter(long... operationIds) {
		return operationFilter(LongStream.of(operationIds)
				.mapToObj(Long::valueOf).collect(Collectors.toSet()));
	}

	default Predicate<Process> operationFilter(Set<Long> operationIds) {
		return p -> p instanceof OperationInfo &&
				operationIds.contains(((OperationInfo) p).getMetadata().getId());
	}

	default Predicate<Process> operationFilter(String classSubstringOrFunctionName) {
		return p -> {
			while (p instanceof ReshapeProducer) {
				p = ((ReshapeProducer<?>) p).getChildren().iterator().next();
			}

			return p.getClass().getSimpleName().contains(classSubstringOrFunctionName) ||
					(p instanceof OperationAdapter && ((OperationAdapter) p).getFunctionName().equals(classSubstringOrFunctionName));
		};
	}

	@Override
	default Console console() { return console; }
}
