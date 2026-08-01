/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.uml.Signature;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Regression coverage for automatic kernel reuse of the synthetic-target
 * producer chains used by the training tests.
 *
 * <p>Asserts the {@link Signature} of every stage of the chain (a null at any
 * stage disables instruction reuse for everything enclosing it) and that
 * repeated naive per-call construction and evaluation — the usage pattern of
 * the training tests, where each sample builds a fresh producer over its
 * input — compiles no additional kernels after the first evaluation.</p>
 */
public class SignatureReuseProbeTest extends TestSuiteBase {

	/** Coefficients matching the synthetic training target functions. */
	private final double[] coeff = { 0.3, -0.2, 0.5, 0.1 };

	/**
	 * Reports per-stage signatures and the compile counts for repeated
	 * per-call construction and evaluation of the training-target chain.
	 */
	@Test(timeout = 300000)
	public void perCallTargetChainReuse() {
		int n = 4;

		PackedCollection in = pack(1.0, 2.0, 3.0, 4.0);

		CollectionProducer stage1 = integers(0, n);
		CollectionProducer stage2 = integers(0, n).mod(coeff.length);
		CollectionProducer stage3 = c(coeff);
		CollectionProducer stage4 = c(coeff).valueAt(integers(0, n).mod(coeff.length));
		CollectionProducer stage5 = cp(in).reshape(shape(n));
		CollectionProducer stage6 = c(coeff).valueAt(integers(0, n).mod(coeff.length))
				.multiply(cp(in).reshape(shape(n)));

		log("stage1Signature=" + Signature.of(stage1));
		log("stage2Signature=" + Signature.of(stage2));
		log("stage3Signature=" + Signature.of(stage3));
		log("stage4Signature=" + Signature.of(stage4));
		log("stage5Signature=" + Signature.of(stage5));
		log("stage6Signature=" + Signature.of(stage6));

		assertNotNull("constant computations must generate a signature", Signature.of(stage3));
		assertNotNull("gather chains must generate a signature", Signature.of(stage4));
		assertNotNull("the complete target chain must generate a signature", Signature.of(stage6));

		int iterations = 300;

		// Warm up so one compile of each involved kernel is already recorded
		PackedCollection warm = new PackedCollection(shape(n));
		c(coeff).valueAt(integers(0, n).mod(coeff.length))
				.multiply(cp(in).reshape(shape(n)))
				.into(warm.traverseEach()).evaluate();

		long cpuBefore = HardwareOperator.cpuCompileCount;
		long gpuBefore = HardwareOperator.gpuCompileCount;
		long start = System.nanoTime();

		for (int i = 0; i < iterations; i++) {
			PackedCollection input = pack(1.0, 2.0, 3.0, 4.0);

			PackedCollection out = new PackedCollection(shape(n));
			c(coeff).valueAt(integers(0, n).mod(coeff.length))
					.multiply(cp(input).reshape(shape(n)))
					.into(out.traverseEach()).evaluate();

			assertEquals(coeff[0], out.toDouble(0));
		}

		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		log("iterations=" + iterations + " elapsedMs=" + elapsedMs +
				" perCallMs=" + (elapsedMs / (double) iterations) +
				" cpuCompiles=" + (HardwareOperator.cpuCompileCount - cpuBefore) +
				" gpuCompiles=" + (HardwareOperator.gpuCompileCount - gpuBefore));

		assertEquals(0.0, HardwareOperator.cpuCompileCount - cpuBefore);
		assertEquals(0.0, HardwareOperator.gpuCompileCount - gpuBefore);
	}

	/**
	 * The same measurement with the coefficient table held as a literal-initialized
	 * {@link PackedCollection} referenced through {@code cp}, instead of a constant
	 * expression built with {@code c(double...)}.
	 */
	@Test(timeout = 300000)
	public void perCallTargetChainReuseWithProviderTable() {
		int n = 4;

		PackedCollection table = pack(0.3, -0.2, 0.5, 0.1);

		PackedCollection in = pack(1.0, 2.0, 3.0, 4.0);

		CollectionProducer chain = cp(table).valueAt(integers(0, n).mod(coeff.length))
				.multiply(cp(in).reshape(shape(n)));
		log("providerChainSignature=" + Signature.of(chain));
		assertNotNull("a provider-based gather chain must generate a signature", Signature.of(chain));

		int iterations = 300;

		PackedCollection warm = new PackedCollection(shape(n));
		cp(table).valueAt(integers(0, n).mod(coeff.length))
				.multiply(cp(in).reshape(shape(n)))
				.into(warm.traverseEach()).evaluate();

		long cpuBefore = HardwareOperator.cpuCompileCount;
		long gpuBefore = HardwareOperator.gpuCompileCount;
		long start = System.nanoTime();

		for (int i = 0; i < iterations; i++) {
			PackedCollection input = pack(1.0, 2.0, 3.0, 4.0);

			PackedCollection out = new PackedCollection(shape(n));
			cp(table).valueAt(integers(0, n).mod(coeff.length))
					.multiply(cp(input).reshape(shape(n)))
					.into(out.traverseEach()).evaluate();

			assertEquals(coeff[0], out.toDouble(0));
		}

		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		log("providerIterations=" + iterations + " elapsedMs=" + elapsedMs +
				" perCallMs=" + (elapsedMs / (double) iterations) +
				" cpuCompiles=" + (HardwareOperator.cpuCompileCount - cpuBefore) +
				" gpuCompiles=" + (HardwareOperator.gpuCompileCount - gpuBefore));

		assertEquals(0.0, HardwareOperator.cpuCompileCount - cpuBefore);
		assertEquals(0.0, HardwareOperator.gpuCompileCount - gpuBefore);
	}
}
