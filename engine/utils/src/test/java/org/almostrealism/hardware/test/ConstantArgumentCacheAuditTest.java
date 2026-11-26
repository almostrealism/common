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

import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.FixedEvaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Guards the contract the constant-argument cache of
 * {@link org.almostrealism.hardware.ProcessDetailsFactory} relies on: an argument is evaluated
 * once and its result cached across executions only when its {@link Evaluable} reports
 * {@link Computable#isConstant()}. A producer that reads memory whose contents change between
 * executions must therefore never report constant, or the cache would serve a stale value and the
 * computation would be silently wrong.
 *
 * <p>Every {@code isConstant()} implementation reachable as a kernel argument either returns the
 * {@link Computable#isConstant() safe default} ({@code false}), delegates to another
 * {@code isConstant()}, or conjoins over its non-destination inputs. The risky leaves are the ones
 * that wrap a {@link PackedCollection} directly — {@link Provider} / {@link FixedEvaluable} — which
 * inherit the {@code false} default. This test pins that they report non-constant, and that
 * non-constancy propagates through a computation built over them and through the compiled evaluable
 * of that computation, so no mutable-memory argument is ever cached.</p>
 */
public class ConstantArgumentCacheAuditTest extends TestSuiteBase implements TestFeatures {
	/** Number of elements in the probe collection. */
	private static final int SIZE = 8;

	/**
	 * A {@link Provider} (and the {@link FixedEvaluable} it implements) wrapping a mutable
	 * {@link PackedCollection} reports non-constant, so the constant cache never caches it.
	 */
	@Test(timeout = 30000)
	public void providerBackedByMutableMemoryIsNotConstant() {
		PackedCollection data = new PackedCollection(shape(SIZE)).fill(1.0);

		Provider<PackedCollection> provider = new Provider<>(data);
		Assert.assertFalse(provider.isConstant());

		FixedEvaluable<PackedCollection> fixed = provider;
		Assert.assertFalse(fixed.isConstant());

		Assert.assertFalse(Computable.constant(provider));
	}

	/**
	 * Non-constancy propagates: a computation built over mutable-memory leaves reports
	 * non-constant, as does the compiled evaluable it produces, so neither the computation nor any
	 * of its arguments is cached as constant.
	 */
	@Test(timeout = 60000)
	public void nonConstancyPropagatesThroughComputationAndEvaluable() {
		PackedCollection a = new PackedCollection(shape(SIZE)).fill(2.0);
		PackedCollection b = new PackedCollection(shape(SIZE)).fill(3.0);

		CollectionProducer leaf = cp(a);
		Assert.assertFalse(((Computable) leaf).isConstant());

		CollectionProducer sum = cp(a).add(cp(b));
		Assert.assertFalse(((Computable) sum).isConstant());

		Evaluable evaluable = sum.get();
		Assert.assertFalse(((Computable) evaluable).isConstant());
	}

	/**
	 * {@link CollectionProducerComputation.IsolatedProcess#isConstant()} only passes along the
	 * constancy of the operation it wraps, so a mutable computation wrapped in an isolated process
	 * must still report non-constant. This pins that override directly: were it to return constant
	 * over a mutable delegate, the wrapped producer could reach the constant-argument cache and be
	 * served a stale value. The compiled evaluable is not re-checked here because the isolated
	 * process delegates directly, so its evaluable is the delegate's, already covered by
	 * {@link #nonConstancyPropagatesThroughComputationAndEvaluable()}.
	 */
	@Test(timeout = 30000)
	public void isolatedProcessOverMutableMemoryIsNotConstant() {
		PackedCollection a = new PackedCollection(shape(SIZE)).fill(2.0);
		PackedCollection b = new PackedCollection(shape(SIZE)).fill(3.0);

		CollectionProducer sum = cp(a).add(cp(b));
		CollectionProducerComputation.IsolatedProcess isolated =
				new CollectionProducerComputation.IsolatedProcess(sum);

		Assert.assertFalse(isolated.isConstant());
		Assert.assertFalse(Computable.constant(isolated));
	}
}
