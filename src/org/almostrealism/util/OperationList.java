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

package org.almostrealism.util;

import io.almostrealism.code.Scope;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.relation.Computation;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.OperationComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class OperationList extends ArrayList<Supplier<Runnable>> implements OperationComputation<Void>, HardwareFeatures {
	@Override
	public Runnable get() {
		if (isComputation()) {
			return compileRunnable(this);
		} else {
			List<Runnable> run = stream().map(Supplier::get).collect(Collectors.toList());
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
