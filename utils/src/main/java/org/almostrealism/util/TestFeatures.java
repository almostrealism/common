/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.cl.HardwareOperator;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface TestFeatures extends CodeFeatures, HardwareFeatures, TensorTestFeatures, TestSettings {
	boolean enableKernelOperationTests = true;

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
		double gap = Hardware.getLocalHardware().isDoublePrecision() ? Math.pow(10, -10) : Math.pow(10, -6);

		if (Math.abs(a - b) >= gap) {
			if (positive) {
				System.err.println("TestFeatures: " + b + " != " + a);
				throw new AssertionError();
			}
		} else if (!positive) {
			System.err.println("TestFeatures: " + b + " == " + a);
			throw new AssertionError();
		}
	}

	default void kernelTest(Supplier<? extends Producer<PackedCollection<?>>> supply, Consumer<PackedCollection<?>> validate) {
		AtomicReference<PackedCollection<?>> outputRef = new AtomicReference<>();

		HardwareOperator.verboseLog(() -> {
			System.out.println("TestFeatures: Running kernel evaluation...");
			PackedCollection<?> output = supply.get().get().evaluate();
			System.out.println("TestFeatures: Output Shape = " + output.getShape() +
					" [" + output.getShape().getCount() + "x" + output.getShape().getSize() + "]");
			System.out.println("TestFeatures: Validating kernel output...");
			validate.accept(output);
			outputRef.set(output);
		});

		if (!enableKernelOperationTests) return;

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
}
