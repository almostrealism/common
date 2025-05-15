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

package org.almostrealism.hardware;

import io.almostrealism.code.Computation;
import io.almostrealism.code.OperationComputation;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.ComputationBase;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

public abstract class OperationComputationAdapter<T>
		extends ComputationBase<T, Void, Runnable>
		implements OperationComputation<Void>, ComputerFeatures {

	@SafeVarargs
	public OperationComputationAdapter(Supplier<Evaluable<? extends T>>... inputArgs) {
		this.setInputs(inputArgs);
		init();
	}

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	/**
	 * A {@link List} of any {@link Computation}s that this operation depends on
	 * in addition to those which result from the {@link #getInputs() inputs}.
	 */
	protected List<Computation<?>> getDependentComputations() {
		return Collections.emptyList();
	}

	@Override
	public long getCountLong() {
		// Try to find a value suitable to the inputs and the dependent computations
		long p = Stream.of(getInputs(), getDependentComputations())
				.flatMap(List::stream)
				.mapToLong(Countable::countLong)
				.distinct().count();

		if (p == 1) {
			return Stream.of(getInputs(), getDependentComputations())
					.flatMap(List::stream)
					.mapToLong(Countable::countLong)
					.distinct().sum();
		}

		// Fallback to a value that is suitable for the dependent computations
		p = getDependentComputations().stream()
				.mapToLong(Countable::countLong).distinct().count();

		if (p == 0) {
			return 1;
		} else if (p == 1) {
			return getDependentComputations().stream()
					.mapToLong(Countable::countLong).distinct().sum();
		}

		// Otherwise, this will not succeed
		throw new UnsupportedOperationException();
	}

	@Override
	public Runnable get() {
		Runnable r = compileRunnable(this);
		if (r instanceof OperationAdapter) {
			((OperationAdapter) r).compile();
		}
		return r;
	}

	@Deprecated
	public Runnable getKernel() {
		Runnable r = Hardware.getLocalHardware().getComputer().compileRunnable(this, true);
		if (r instanceof OperationAdapter) {
			((OperationAdapter) r).compile();
		}
		return r;
	}
}
