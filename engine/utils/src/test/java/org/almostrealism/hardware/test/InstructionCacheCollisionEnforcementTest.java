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
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.arguments.ProcessArgumentMap;
import org.almostrealism.hardware.mem.KernelConstantProviderSupplier;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.hardware.mem.MemoryRegionList;
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
	 * One assignment kernel must serve aggregated destinations whose roots differ in length,
	 * reading its source correctly and writing only the destination's view of each root.
	 *
	 * <p>Aggregation lays roots out one after another, so a root's length decides the offset
	 * of every aggregated argument placed after it. The assignment's signature records that
	 * its destination is aggregated but not the length of the destination's root, which is
	 * sound only because the destination is placed after every argument of the value: the
	 * value's inputs are prepared before the assignment's own arguments are assigned. The
	 * three assignments below share one signature, so the later ones reuse the first kernel,
	 * and reuse verifies that the aggregate positions match; a layout that placed the
	 * destination before the source would fail that verification here.</p>
	 */
	@Test(timeout = 60000)
	public void assignmentReusedAcrossAggregatedDestinationRootLengths() {
		int length = 64;
		PackedCollection source = integers(1, length + 1).evaluate();
		int[] rootLengths = { 128, 576, 256 };
		String firstSignature = null;

		for (int rootLength : rootLengths) {
			PackedCollection root = new PackedCollection(rootLength).fill(-1.0);
			PackedCollection destination = root.range(shape(length));
			Assignment<PackedCollection> scale =
					a("scaleProbe", p(destination.each()), cp(source.each()).multiply(2.0));

			String signature = scale.signature();
			Assert.assertNotNull(signature);
			Assert.assertTrue(signature, signature.contains("&aggregateDestination"));
			if (firstSignature == null) firstSignature = signature;
			Assert.assertEquals(firstSignature, signature);

			scale.get().run();

			double[] values = root.toArray();
			for (int i = 0; i < rootLength; i++) {
				assertEquals(i < length ? 2.0 * (i + 1) : -1.0, values[i]);
			}
		}
	}

	/**
	 * Before compilation, only a declared provider whose root memory is within the
	 * aggregation size limit is reported as an aggregation target. The root decides, not
	 * the view: a small view of a large root is not folded, and a view of a small root is.
	 * Computed producers and kernel-owned constant memory are never targets, and
	 * {@link Provider#valueOf(Supplier, Class)} yields nothing for them.
	 */
	@Test(timeout = 60000)
	public void aggregationTargetOfProducerBeforeCompilation() {
		int limit = MemoryDataArgumentMap.maxAggregateLength;
		PackedCollection small = new PackedCollection(limit / 4);
		PackedCollection atLimit = new PackedCollection(limit);
		PackedCollection large = new PackedCollection(limit + 1);

		Assert.assertTrue(MemoryDataArgumentMap.isAggregationTarget(p(small)));
		Assert.assertTrue(MemoryDataArgumentMap.isAggregationTarget(p(atLimit)));
		Assert.assertFalse(MemoryDataArgumentMap.isAggregationTarget(p(large)));
		Assert.assertTrue(MemoryDataArgumentMap.isAggregationTarget(p(small.range(shape(2), 1))));
		Assert.assertFalse(MemoryDataArgumentMap.isAggregationTarget(p(large.range(shape(2), 1))));

		CollectionProducer computed = cp(small).multiply(2.0);
		Assert.assertFalse(MemoryDataArgumentMap.isAggregationTarget(computed));
		Assert.assertNull(Provider.valueOf(computed, MemoryData.class));
		Assert.assertNull(Provider.valueOf(null, MemoryData.class));
		Assert.assertSame(small, Provider.valueOf(p(small), MemoryData.class));
		Assert.assertSame(small, Provider.valueOf(p(small), PackedCollection.class));
		Assert.assertNull(Provider.valueOf(p(small), String.class));

		Assert.assertFalse(MemoryDataArgumentMap.isAggregationTarget(
				new KernelConstantProviderSupplier(small)));
	}

	/**
	 * The regions an assignment writes are resolved from its provider destination before
	 * compilation, so assignments into overlapping views of the same memory overlap and
	 * assignments into disjoint views do not. An assignment whose destination is computed
	 * rather than provided has no statically known write region.
	 */
	@Test(timeout = 60000)
	public void assignmentWriteRegionsResolveProviderDestination() {
		PackedCollection root = new PackedCollection(8);
		PackedCollection source = new PackedCollection(4);

		MemoryRegionList low = MemoryRegionList.writes(
				a("low", p(root.range(shape(4), 0).each()), cp(source.each())));
		MemoryRegionList middle = MemoryRegionList.writes(
				a("middle", p(root.range(shape(4), 2).each()), cp(source.each())));
		MemoryRegionList high = MemoryRegionList.writes(
				a("high", p(root.range(shape(4), 4).each()), cp(source.each())));
		MemoryRegionList computed = MemoryRegionList.writes(
				a("computed", cp(root.range(shape(4), 0).each()).multiply(2.0), cp(source.each())));

		Assert.assertFalse(low.isEmpty());
		Assert.assertTrue(low.overlaps(middle));
		Assert.assertTrue(middle.overlaps(high));
		Assert.assertFalse(low.overlaps(high));
		Assert.assertTrue(computed.isEmpty());
	}

	/**
	 * An assignment's signature records whether its destination is aggregated, so the same
	 * value assigned to destinations on each side of the aggregation size limit yields two
	 * signatures that differ only by the aggregation marker.
	 */
	@Test(timeout = 60000)
	public void assignmentSignatureMarksAggregatedDestination() {
		int limit = MemoryDataArgumentMap.maxAggregateLength;
		PackedCollection source = new PackedCollection(8);

		String aggregated = a("markProbe", p(new PackedCollection(limit).range(shape(8)).each()),
				cp(source.each()).multiply(3.0)).signature();
		String separate = a("markProbe", p(new PackedCollection(limit + 1).range(shape(8)).each()),
				cp(source.each()).multiply(3.0)).signature();

		Assert.assertNotNull(aggregated);
		Assert.assertNotNull(separate);
		Assert.assertTrue(aggregated, aggregated.contains("&aggregateDestination"));
		Assert.assertFalse(separate, separate.contains("&aggregateDestination"));
		Assert.assertEquals(separate, aggregated.replace("&aggregateDestination", ""));
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
