/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.Scope;
import io.almostrealism.code.Computation;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.OperationComputation;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.relation.Compactable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class OperationList extends ArrayList<Supplier<Runnable>> implements OperationComputation<Void>, HardwareFeatures {
	private boolean enableCompilation;

	public OperationList() { this(true); }

	public OperationList(boolean enableCompilation) { this.enableCompilation = enableCompilation; }

	@Override
	public Runnable get() {
		if (enableCompilation && isComputation()) {
			OperationAdapter op = (OperationAdapter) compileRunnable(this);
			op.compile();
			return (Runnable) op;
		} else {
			List<Runnable> run = stream().map(Supplier::get).collect(Collectors.toList());
			run.stream()
					.map(r -> r instanceof OperationAdapter ? (OperationAdapter) r : null)
					.filter(Objects::nonNull)
					.forEach(OperationAdapter::compile);
			return () -> run.forEach(Runnable::run);
		}
	}

	public boolean isComputation() {
		int nonComputations = stream().mapToInt(o -> {
			if (o instanceof OperationList) {
				return ((OperationList) o).isComputation() ? 0 : 1;
			} else {
				return o instanceof Computation ? 0 : 1;
			}
		}).sum();

		return nonComputations == 0;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		ScopeLifecycle.prepareArguments(stream(), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		ScopeLifecycle.prepareScope(stream(), manager);
	}

	@Override
	public Scope<Void> getScope(NameProvider provider) {
		if (!isComputation()) {
			throw new IllegalArgumentException("OperationList cannot be compiled to a Scope unless all embedded Operations are Computations");
		}

		Scope scope = new Scope(provider.getFunctionName());
		stream().map(o -> ((Computation) o).getScope(provider)).forEach(scope::add);
		return scope;
	}

	@Override
	public void compact() {
		stream().map(o -> o instanceof Compactable ? (Compactable) o : null)
				.filter(Objects::nonNull).forEach(Compactable::compact);
	}
}
