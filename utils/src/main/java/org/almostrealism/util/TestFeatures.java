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
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.OperationProfile;
import io.almostrealism.code.OperationProfileNode;
import io.almostrealism.expression.Expression;
import io.almostrealism.profile.CompilationProfile;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.TraversableRepeatedProducerComputation;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.kernel.KernelSeriesCache;
import org.almostrealism.io.Console;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
		double gap = Math.pow(10, 3) * Hardware.getLocalHardware().getPrecision().epsilon();
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

	default void kernelTest(Supplier<? extends Producer<PackedCollection<?>>> supply, Consumer<PackedCollection<?>> validate) {
		kernelTest(supply, validate, true, true, true);
	}

	default void kernelTest(Supplier<? extends Producer<PackedCollection<?>>> supply, Consumer<PackedCollection<?>> validate,
							boolean kernel, boolean operation, boolean optimized) {
		AtomicReference<PackedCollection<?>> outputRef = new AtomicReference<>();

		if (kernel) {
			HardwareOperator.verboseLog(() -> {
				System.out.println("TestFeatures: Running kernel evaluation...");
				Producer<PackedCollection<?>> p = supply.get();
				PackedCollection<?> output = p.get().evaluate();
				System.out.println("TestFeatures: Output Shape = " + output.getShape() +
						" [" + output.getShape().getCountLong() + "x" + output.getShape().getSize() + "]");
				System.out.println("TestFeatures: Validating kernel output...");
				validate.accept(output);
				outputRef.set(output);
			});
		} else {
			outputRef.set(new PackedCollection<>(shape(supply.get())));
		}

		if (operation) {
			outputRef.get().clear();

			HardwareOperator.verboseLog(() -> {
				PackedCollection<?> output = outputRef.get();

				System.out.println("TestFeatures: Running kernel operation...");
				OperationList op = new OperationList();
				op.add(output.getAtomicMemLength(), supply.get(), p(output));
				op.get().run();
				System.out.println("TestFeatures: Validating kernel output...");
				validate.accept(output);
			});
		}

		if (optimized) {
			outputRef.get().clear();

			HardwareOperator.verboseLog(() -> {
				PackedCollection<?> output = outputRef.get();
				PackedCollection<?> dest = new PackedCollection<>(output.getShape());

				System.out.println("TestFeatures: Running optimized kernel operation...");
				OperationList op = new OperationList();
				op.add(output.getAtomicMemLength(), supply.get(), p(output));
				op.add(copy(p(output), p(dest), output.getMemLength()));

				ParallelProcess<?, Runnable> p = op.optimize();
				p.get().run();
				System.out.println("TestFeatures: Validating optimized kernel output...");
				validate.accept(output);
				validate.accept(dest);
			});
		}
	}

	default void initKernelMetrics() {
		initKernelMetrics(new OperationProfile("HardwareOperator",
				OperationProfile.appendContext(OperationMetadata::getDisplayName)));
	}

	default void initKernelMetrics(OperationProfile profile) {
		if (profile instanceof OperationProfileNode) {
			AbstractComputeContext.compilationProfile = ((OperationProfileNode) profile).getCompilationProfile();
		} else {
			AbstractComputeContext.compilationProfile = new CompilationProfile("default",
					OperationProfile.appendContext(OperationMetadata::getDisplayName));
		}

		HardwareOperator.profile = profile;
		AcceleratedComputationOperation.clearTimes();
	}

	default void logKernelMetrics() {
		logKernelMetrics(null);
	}

	default void logKernelMetrics(OperationProfile profile) {
		if (profile != null) profile.print();
		if (HardwareOperator.profile != null && HardwareOperator.profile != profile)
			HardwareOperator.profile.print();

		AcceleratedComputationOperation.printTimes();
		log("KernelSeriesCache min nodes - " + KernelSeriesCache.minNodeCountMatch +
				" (match) | " + KernelSeriesCache.minNodeCountCache + " (cache)");
		log("KernelSeriesCache size = " + KernelSeriesCache.defaultMaxExpressions +
				" expressions | " + KernelSeriesCache.defaultMaxEntries + " entries | "
				+ (KernelSeriesCache.enableCache ? "on" : "off"));
		log("Expression kernelSeq cache is " + (Expression.enableKernelSeqCache ? "on" : "off"));
		log("TraversableRepeatedProducerComputation isolation count threshold = " + TraversableRepeatedProducerComputation.isolationCountThreshold);
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
