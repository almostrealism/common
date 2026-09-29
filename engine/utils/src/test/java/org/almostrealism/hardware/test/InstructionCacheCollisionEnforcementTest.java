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

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.arguments.ProcessArgumentMap;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Regression coverage for instruction cache collision enforcement.
 *
 * <p>An instruction cache collision occurs when two distinct kernels are matched to one
 * signature. The aggregate argument layout is a deterministic byproduct of a computation's
 * inputs, so it is part of kernel identity; likewise, a reusing computation must supply a
 * substitution for every positioned argument of the compiled scope. Collisions must always
 * surface as exceptions — never be absorbed by quiet recompilation or a silent fallback —
 * so these tests pin the supporting contracts:</p>
 * <ul>
 *   <li>{@link ProcessArgumentMap#verifySubstitutions(String)} throws when a positioned
 *       argument received no substitution, at binding time rather than first evaluation.</li>
 *   <li>{@link MemoryDataArgumentMap}'s aggregate supplier throws when asked for a buffer
 *       from a map that aggregated nothing, instead of delivering null to a kernel.</li>
 * </ul>
 *
 * <p>The layout comparison itself (recorded at compile via
 * {@code ComputableInstructionSetManager.setAggregateLayout}, verified in
 * {@code AcceleratedComputationOperation.rebindAggregateForReuse}) is exercised end-to-end
 * by any workload whose reuse actually collides, since it fails the operation loudly.</p>
 */
public class InstructionCacheCollisionEnforcementTest extends TestSuiteBase {

	/**
	 * A positioned argument with no substitution must fail verification at binding
	 * time, and the same map must pass once substitutions for the full tree are
	 * registered.
	 */
	@Test(timeout = 60000)
	public void verifySubstitutionsDetectsMissingSubstitution() {
		CollectionProducer sum = c(1.0).add(c(2.0));
		Process<?, ?> process = (Process<?, ?>) sum;

		Process<?, ?> child = null;
		for (Process<?, ?> candidate : process.getChildren()) {
			if (candidate instanceof Producer) {
				child = candidate;
				break;
			}
		}
		Assert.assertNotNull("The computation must have a Producer child to position", child);

		ArrayVariable<?> argument = CollectionVariable.create("arg0", (Supplier) child);
		ProcessArgumentMap map = new ProcessArgumentMap(process, List.of(argument));

		try {
			map.verifySubstitutions("collisionEnforcementProbe");
			Assert.fail("A positioned argument with no substitution must fail verification");
		} catch (HardwareException e) {
			log("verifyFailureMessage=" + e.getMessage());
		}

		map.putSubstitutions(process);
		map.verifySubstitutions("collisionEnforcementProbe");
	}

	/**
	 * The same constant chain evaluated twice in one JVM must reuse the compiled kernel and
	 * produce identical, correct results.
	 *
	 * <p>The chain's kernel references a compiler-materialized series cache buffer
	 * (a {@code KernelSeriesCache} table of {@code count * 32 = 992} elements). That buffer
	 * must be a standalone kernel argument — never an aggregation target — or the second
	 * evaluation's reuse binding faces an aggregate layout it cannot reproduce, which is
	 * exactly the collision this test originally exposed via
	 * {@code TemporalFeatures.lowPassCoefficients}.</p>
	 */
	@Test(timeout = 60000)
	public void compilerMaterializedCacheSurvivesReuse() {
		double[] table = IntStream.range(0, 31).mapToDouble(i -> i).toArray();

		PackedCollection first = c(table).subtract(c(15.0)).multiply(c(Math.PI)).get().evaluate();
		PackedCollection second = c(table).subtract(c(15.0)).multiply(c(Math.PI)).get().evaluate();

		for (int i = 0; i < table.length; i++) {
			double expected = (table[i] - 15.0) * Math.PI;
			assertEquals(expected, first.toDouble(i));
			assertEquals(expected, second.toDouble(i));
		}
	}

	/**
	 * The same size-generic assignment applied to destinations on both sides of the
	 * aggregation size limit must compile into distinct kernels, and each must still be
	 * reused across destinations of different sizes on its own side of the limit.
	 *
	 * <p>{@code Assignment} omits the destination size from its signature so that one kernel
	 * serves every size. Whether the destination is folded into the aggregate argument,
	 * however, depends on the size of its root memory, and the fold is baked into the
	 * kernel. Clearing a 1536-element gradient and then a 576-element one (as a
	 * {@code BranchBlock} backward pass does) previously matched both to one signature and
	 * failed the second with an instruction cache collision.</p>
	 */
	@Test(timeout = 60000)
	public void assignmentAcrossAggregationLimitDoesNotCollide() {
		int limit = MemoryDataArgumentMap.maxAggregateLength;
		int[] sizes = { limit + 512, limit / 2 + 64, limit * 2, limit / 8 };

		for (int size : sizes) {
			PackedCollection destination = new PackedCollection(size).fill(1.0);
			a("clearProbe", p(destination.each()), c(0.0)).get().run();

			double[] values = destination.toArray();
			for (int i = 0; i < size; i++) {
				assertEquals(0.0, values[i]);
			}
		}
	}

	/**
	 * Requesting the aggregate buffer from an argument map that aggregated nothing must
	 * throw rather than deliver a null buffer to a kernel argument.
	 */
	@Test(timeout = 60000)
	public void emptyAggregateSupplierRefusesToProvide() {
		MemoryDataArgumentMap map = MemoryDataArgumentMap.create(null, length -> null);

		try {
			map.getAggregateSupplier().get();
			Assert.fail("An empty aggregate must never be provided as a buffer");
		} catch (HardwareException e) {
			log("aggregateFailureMessage=" + e.getMessage());
		}
	}
}
